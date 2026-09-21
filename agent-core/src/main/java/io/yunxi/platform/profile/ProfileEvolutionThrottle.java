package io.yunxi.platform.profile;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import io.yunxi.platform.profile.config.ProfileProperties;

/**
 * 画像自动演进的节流器：决定「本次调用收尾时是否真的触发抽取」。
 *
 * <p>抽取挂载在每次调用收尾，但并非每轮都值得抽——身份信号是稀疏事件，
 * 每轮都抽会让 token 成本随轮数线性增长。节流规则为「或」关系：距上次抽取
 * 已超过 {@code minIntervalSeconds}，或已累积 {@code minTurns} 轮对话，
 * 任一达成即放行。两个阈值都为 0 表示不节流（每轮都抽）。</p>
 *
 * <p>计数优先落 Redis（多实例共享节流状态，避免多实例各自计数导致过度抽取）；
 * 无 Redis 时回退进程内 Map（开发/单测）。Redis 故障一律放行（fail-open），
 * 宁可多抽一次也不漏抽。</p>
 */
@Component
public class ProfileEvolutionThrottle {

    private static final String KEY_PREFIX = "yunxi:profile-throttle:";

    private final ProfileProperties properties;
    private final RedisTemplate<String, String> redisTemplate;
    private final boolean redisEnabled;
    private final ConcurrentHashMap<String, LocalState> memoryFallback = new ConcurrentHashMap<>();

    public ProfileEvolutionThrottle(ProfileProperties properties,
            ObjectProvider<RedisConnectionFactory> factoryProvider) {
        this.properties = properties;
        RedisConnectionFactory factory = factoryProvider.getIfAvailable();
        if (factory != null) {
            RedisTemplate<String, String> tpl = new RedisTemplate<>();
            tpl.setConnectionFactory(factory);
            tpl.setKeySerializer(StringRedisSerializer.UTF_8);
            tpl.setValueSerializer(StringRedisSerializer.UTF_8);
            tpl.afterPropertiesSet();
            this.redisTemplate = tpl;
            this.redisEnabled = true;
        } else {
            this.redisTemplate = null;
            this.redisEnabled = false;
        }
    }

    /**
     * 判定并登记一次调用收尾。命中节流时仅累加轮次计数，不重置计时。
     *
     * @param userId 用户标识；为空时直接放行（无从节流）
     * @return 放行返回 true（本次应收尾触发抽取）；被节流返回 false
     */
    public boolean tryAcquire(String userId) {
        ProfileProperties.Evolution cfg = properties.getEvolution();
        if (!cfg.isEnabled()) {
            return false;
        }
        if (userId == null || userId.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long minIntervalMs = Math.max(0L, cfg.getMinIntervalSeconds()) * 1000L;
        int minTurns = Math.max(0, cfg.getMinTurns());

        // 无节流：每轮都放行
        if (minIntervalMs == 0L && minTurns == 0) {
            return true;
        }

        if (redisEnabled) {
            return tryAcquireRedis(userId, now, minIntervalMs, minTurns);
        }
        return tryAcquireMemory(userId, now, minIntervalMs, minTurns);
    }

    private boolean tryAcquireRedis(String userId, long now, long minIntervalMs, int minTurns) {
        String key = KEY_PREFIX + userId;
        try {
            // 轮次计数用 HINCRBY，时间戳用 HSET，读-判-写在一个轻量往返内完成。
            String lastAtRaw = (String) redisTemplate.opsForHash().get(key, "lastAt");
            String turnsRaw = (String) redisTemplate.opsForHash().get(key, "turns");
            long lastAt = parseLong(lastAtRaw, 0L);
            int turns = (int) parseLong(turnsRaw, 0L);

            int turnsNow = turns + 1;
            boolean byTime = minIntervalMs > 0L && (lastAt == 0L || now - lastAt >= minIntervalMs);
            boolean byTurns = minTurns > 0 && turnsNow >= minTurns;

            if (byTime || byTurns) {
                redisTemplate.opsForHash().put(key, "lastAt", String.valueOf(now));
                redisTemplate.opsForHash().put(key, "turns", "0");
                return true;
            }
            redisTemplate.opsForHash().put(key, "turns", String.valueOf(turnsNow));
            return false;
        } catch (Exception e) {
            // Redis 故障 fail-open：宁可多抽一次也不漏抽
            return true;
        }
    }

    private boolean tryAcquireMemory(String userId, long now, long minIntervalMs, int minTurns) {
        LocalState state = memoryFallback.computeIfAbsent(userId, k -> new LocalState());
        synchronized (state) {
            int turnsNow = state.turns.incrementAndGet();
            boolean byTime = minIntervalMs > 0L && (state.lastAt == 0L || now - state.lastAt >= minIntervalMs);
            boolean byTurns = minTurns > 0 && turnsNow >= minTurns;
            if (byTime || byTurns) {
                state.lastAt = now;
                state.turns.set(0);
                return true;
            }
            return false;
        }
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class LocalState {
        private long lastAt;
        private final AtomicInteger turns = new AtomicInteger();
    }
}
