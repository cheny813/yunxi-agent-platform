package io.yunxi.fabric;

import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 基于全局 Lettuce（StringRedisTemplate）的跨实例沙箱执行守卫，语义对齐框架 RedisSandboxExecutionGuard
 * （SET NX + 租约 TTL + Lua 原子释放）。
 *
 * <p>框架 extensions-redis 的 Redis 守卫使用 Jedis(UnifiedJedis)，与 yunxi 全局 Redis 基础设施
 * （Lettuce）不兼容；在 yunxi 产品平面提供 Lettuce 适配，复用框架 SandboxExecutionGuard
 * 接口语义，不碰框架源码、不私建 Redis 客户端。</p>
 */
public class RedisSandboxExecutionGuard implements SandboxExecutionGuard {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long leaseTtlMillis;
    private final long retryIntervalMillis;
    private static final Logger log = LoggerFactory.getLogger(RedisSandboxExecutionGuard.class);

    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    public RedisSandboxExecutionGuard(StringRedisTemplate redisTemplate, String keyPrefix) {
        this(redisTemplate, keyPrefix, 60_000L, 100L);
    }

    public RedisSandboxExecutionGuard(
            StringRedisTemplate redisTemplate, String keyPrefix, long leaseTtlMillis, long retryIntervalMillis) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.leaseTtlMillis = leaseTtlMillis;
        this.retryIntervalMillis = retryIntervalMillis;
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String redisKey = keyPrefix + sanitize(key.toString());
        String token = UUID.randomUUID().toString();
        while (true) {
            Boolean acquired =
                    redisTemplate.opsForValue().setIfAbsent(redisKey, token, Duration.ofMillis(leaseTtlMillis));
            if (Boolean.TRUE.equals(acquired)) {
                return () -> release(redisKey, token);
            }
            Thread.sleep(retryIntervalMillis);
        }
    }

    private void release(String redisKey, String token) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_LUA, Long.class);
            redisTemplate.execute(script, List.of(redisKey), token);
        } catch (Exception e) {
            // 对齐框架契约：释放失败必须记录并吞掉，绝不抛出，避免污染 harness 沙箱释放后的清理路径。
            log.warn("释放沙箱执行锁失败（key={}），已忽略以避免污染调用链清理路径", redisKey, e);
        }
    }

    private static String sanitize(String raw) {
        byte[] bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
