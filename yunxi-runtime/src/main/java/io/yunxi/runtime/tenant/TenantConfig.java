package io.yunxi.runtime.tenant;

/**
 * 多租户轴配置。
 *
 * <p>默认 {@code enabled=false} → 装饰器 pass-through（不改写任何键），保证存量数据（无租户前缀）零迁移、
 * 行为不变。启用后由 {@code defaultTenant} 承载「未解析到租户」时的单租户回落。
 *
 * <p>键空间隔离而非实体字段散落：租户维度只经 {@link TenantContext} 在键计算时读取，业务处不出现 tenant 字段。
 */
public record TenantConfig(boolean enabled, String defaultTenant) {

    /** 默认关闭：装饰器完全透传，与历史行为一致。 */
    public static final TenantConfig DISABLED = new TenantConfig(false, "default");

    public TenantConfig {
        if (defaultTenant == null || defaultTenant.isBlank()) {
            defaultTenant = "default";
        }
    }
}
