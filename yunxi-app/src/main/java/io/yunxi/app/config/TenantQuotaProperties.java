package io.yunxi.app.config;

import io.yunxi.definition.TenantQuota;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 租户配额配置（{@code yunxi.tenant.quota.*}）。默认关闭（两默认限额均为 {@code null} → 不限额），
 * 仅对显式配置了 {@code tenants.<tenantId>} 的租户，或设置了全局默认的租户生效；未配置的租户 fail-open。
 *
 * <p>每租户覆盖字段（{@code dailyRequestLimit} / {@code concurrentSessionLimit} / {@code storageBytesLimit}）为空时回落全局默认，
 * 实现「默认 + 覆盖」合并。该配置由 {@code TenantQuotaMiddleware} 在运行时读取派生，不进入 {@code Tenant} 定义。
 */
@ConfigurationProperties(prefix = "yunxi.tenant.quota")
public class TenantQuotaProperties {

    private Long defaultDailyRequestLimit;
    private Long defaultConcurrentSessionLimit;
    private Long defaultStorageBytesLimit;
    private Map<String, TenantQuota> tenants = new HashMap<>();

    public Long getDefaultDailyRequestLimit() {
        return defaultDailyRequestLimit;
    }

    public void setDefaultDailyRequestLimit(Long defaultDailyRequestLimit) {
        this.defaultDailyRequestLimit = defaultDailyRequestLimit;
    }

    public Long getDefaultConcurrentSessionLimit() {
        return defaultConcurrentSessionLimit;
    }

    public void setDefaultConcurrentSessionLimit(Long defaultConcurrentSessionLimit) {
        this.defaultConcurrentSessionLimit = defaultConcurrentSessionLimit;
    }

    public Long getDefaultStorageBytesLimit() {
        return defaultStorageBytesLimit;
    }

    public void setDefaultStorageBytesLimit(Long defaultStorageBytesLimit) {
        this.defaultStorageBytesLimit = defaultStorageBytesLimit;
    }

    public Map<String, TenantQuota> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, TenantQuota> tenants) {
        this.tenants = tenants;
    }

    /**
     * 合并全局默认与每租户覆盖，得到该租户的生效配额。两维度均为 {@code null} 时返回空（不限额）。
     */
    public Optional<TenantQuota> resolve(String tenantId) {
        TenantQuota override = tenants.get(tenantId);
        Long daily = override != null && override.dailyRequestLimit() != null
                ? override.dailyRequestLimit() : defaultDailyRequestLimit;
        Long concurrent = override != null && override.concurrentSessionLimit() != null
                ? override.concurrentSessionLimit() : defaultConcurrentSessionLimit;
        Long storage = override != null && override.storageBytesLimit() != null
                ? override.storageBytesLimit() : defaultStorageBytesLimit;
        if (daily == null && concurrent == null && storage == null) {
            return Optional.empty();
        }
        return Optional.of(new TenantQuota(daily, concurrent, storage));
    }
}
