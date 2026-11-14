package io.yunxi.app.tenant;

import io.yunxi.app.config.TenantQuotaProperties;
import io.yunxi.definition.TenantStorageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于平台全局 StringRedisTemplate 的租户存储计量（与 {@code TenantQuotaMiddleware} 同 Redis 后端，复用单一计数底座）。
 *
 * <p>计数键 {@code yunxi:tenant-storage:{tid}}：写入 INCR、删除 DECR（v1 按写入次数近似；预留 value 序列化字节估算扩展点）。
 * 累计运行总量，不设置 TTL。Redis 不可用 / {@code StringRedisTemplate} 缺失 / {@code storageBytesLimit} 为 null /
 * tenant 为 null 时 fail-open 静默跳过，不阻断既有写入（与 3b 配额闸门一致）。
 *
 * <p>软告警：INCR 后若计数 > 解析到的 {@code storageBytesLimit}，发 WARN（不抛异常、不阻断写入）。v1 仅观测，硬限为后续项。
 */
@Component
public class RedisTenantStorageMeter implements TenantStorageMeter {

    private static final Logger log = LoggerFactory.getLogger(RedisTenantStorageMeter.class);
    private static final String STORAGE_KEY = "yunxi:tenant-storage:%s";

    private final TenantQuotaProperties properties;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public RedisTenantStorageMeter(TenantQuotaProperties properties,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this.properties = properties;
        this.redisProvider = redisProvider;
    }

    @Override
    public void recordWrite(String tenantId) {
        adjust(tenantId, 1L);
    }

    @Override
    public void recordDelete(String tenantId) {
        adjust(tenantId, -1L);
    }

    private void adjust(String tenantId, long delta) {
        if (tenantId == null) {
            return;
        }
        StringRedisTemplate rt = redisProvider.getIfAvailable();
        if (rt == null) {
            return;
        }
        String key = storageKey(tenantId);
        try {
            Long after = delta > 0 ? rt.opsForValue().increment(key) : rt.opsForValue().decrement(key);
            if (after == null) {
                return;
            }
            Long limit = properties.resolve(tenantId).map(tq -> tq.storageBytesLimit()).orElse(null);
            if (limit != null && after > limit) {
                log.warn("[TenantStorage] 租户 {} 存储用量 {} 已超软阈值 {}（v1 仅观测不阻断，硬限为后续项）",
                        tenantId, after, limit);
            }
        } catch (RuntimeException e) {
            log.warn("[TenantStorage] Redis 计量失败，fail-open 跳过 key={}：{}", key, e.getMessage());
        }
    }

    private static String storageKey(String tenantId) {
        return String.format(STORAGE_KEY, tenantId);
    }
}
