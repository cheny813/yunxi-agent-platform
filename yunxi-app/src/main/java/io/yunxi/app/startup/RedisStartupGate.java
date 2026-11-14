package io.yunxi.app.startup;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis SETNX 的启动闸门。并发启动时仅首个抢到 leader 锁的实例全量布建，
 * 其余实例跳过本地加载；首实例布建完成后释放锁，允许后续实例在需要时自行布建（正确性不受影响）。
 *
 * <p>fail-open：Redis 不可用或协调调用异常时一律放行本实例，避免 Redis 故障阻断启动。</p>
 */
public class RedisStartupGate implements StartupGate {

    private static final Logger log = LoggerFactory.getLogger(RedisStartupGate.class);

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final String lockKey;
    private final int allowConcurrentLeaders;
    private final Duration ttl;

    public RedisStartupGate(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            String lockKey,
            int allowConcurrentLeaders,
            Duration ttl) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.lockKey = lockKey;
        this.allowConcurrentLeaders = allowConcurrentLeaders;
        this.ttl = ttl;
    }

    @Override
    public boolean tryAcquire() {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            // fail-open：Redis 不可用 → 放行本实例（降级为无协调的全量布建）
            return true;
        }
        try {
            if (allowConcurrentLeaders <= 1) {
                Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, UUID.randomUUID().toString(), ttl);
                return acquired != null && acquired;
            }
            // >1 并发 leader：暂退化为放行（多 leader 计数协调后续增强）
            return true;
        } catch (RuntimeException e) {
            log.warn("StartupGate：Redis 协调失败，fail-open 放行本实例", e);
            return true;
        }
    }

    @Override
    public void release() {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(lockKey);
        } catch (RuntimeException e) {
            log.warn("StartupGate：释放 leader 锁失败（TTL 自动过期）", e);
        }
    }
}
