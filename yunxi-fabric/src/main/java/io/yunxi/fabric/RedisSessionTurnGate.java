package io.yunxi.fabric;

import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis 的跨实例轮次闸门。以 {@code SET key token NX PX(ttl)} 抢占锁，后台定时续租，
 * 关闭时按 token 比对后删除（compare-and-del），避免误删他人锁。
 *
 * <p>抢占在 {@code ACQUIRE_TIMEOUT_MS} 内重试，超时抛 {@link TurnBusyException}，
 * 使并发入站被明确拒绝而非双跑——这是分布式会话正确性的第一道闸门（让冲突不发生）。
 * 锁到期（续租失败 / 进程崩溃）由 Redis TTL 自然释放，无需强一致。
 *
 * <p>闸门复用全局共享 {@link StringRedisTemplate}（由 Spring Boot 自动配置的全局
 * {@code RedisConnectionFactory} 支撑，绑定 config/redis.yml），不额外创建 Redis 客户端。</p>
 *
 * <p>常量与 AgentScope-Java 的 {@code ControlPlaneSessionTurnGate} 对齐，便于跨实现理解。</p>
 */
public final class RedisSessionTurnGate implements SessionTurnGate {

    private static final long LEASE_TTL_MS = 60_000L;
    private static final long RENEW_INTERVAL_MS = 20_000L;
    private static final long ACQUIRE_TIMEOUT_MS = 3_000L;
    private static final long RETRY_INTERVAL_MS = 500L;

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final ScheduledExecutorService renewExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yunxi-turn-gate-renew");
        t.setDaemon(true);
        return t;
    });

    /** 构造跨实例闸门：注入全局共享 Redis 模板与键前缀。 */
    public RedisSessionTurnGate(StringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    /** 把会话键拼为 Redis 锁键 {@code <prefix>turn:<key>}。 */
    private String lockKey(String key) {
        return keyPrefix + "turn:" + key;
    }

    /** 抢占锁：在超时内以 NX+PX 重试，成功后返回带续租的租约；超时抛 {@link TurnBusyException} 拒绝并发入站。 */
    @Override
    public TurnLease acquire(String key) throws InterruptedException, TurnBusyException {
        String lk = lockKey(key);
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT_MS;
        while (true) {
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(lk, token, Duration.ofMillis(LEASE_TTL_MS));
            if (Boolean.TRUE.equals(ok)) {
                return new RedisTurnLease(lk, token);
            }
            if (System.currentTimeMillis() > deadline) {
                throw new TurnBusyException("turn busy: " + key);
            }
            Thread.sleep(RETRY_INTERVAL_MS);
        }
    }

    /** 判断该会话是否仍持有锁（锁键存在）。 */
    @Override
    public boolean isRunning(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(key)));
    }

    private final class RedisTurnLease implements TurnLease {

        private final String lockKey;
        private final String token;
        private final ScheduledFuture<?> renewFuture;
        private volatile boolean closed = false;

        RedisTurnLease(String lockKey, String token) {
            this.lockKey = lockKey;
            this.token = token;
            this.renewFuture = renewExecutor.scheduleAtFixedRate(
                    this::renew, RENEW_INTERVAL_MS, RENEW_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        private void renew() {
            if (closed) {
                return;
            }
            try {
                // 仅当本实例仍持有锁时才续租；锁已不在则静默停止。
                redisTemplate.opsForValue()
                        .setIfPresent(lockKey, token, Duration.ofMillis(LEASE_TTL_MS));
            } catch (RuntimeException ignored) {
                // 续租失败（如 Redis 暂不可达）：锁到期自然释放，生产应接可观测告警。
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            renewFuture.cancel(false);
            try {
                // compare-and-del：仅当仍持有本 token 时删除，避免误删他人锁。
                String current = redisTemplate.opsForValue().get(lockKey);
                if (token.equals(current)) {
                    redisTemplate.delete(lockKey);
                }
            } catch (RuntimeException ignored) {
                // 释放失败（如 Redis 不可达）：锁到期自动释放，无需强一致。
            }
        }
    }

    /** 仅供测试 / 优雅关闭：停掉续租线程池。 */
    public void shutdown() {
        renewExecutor.shutdownNow();
    }
}
