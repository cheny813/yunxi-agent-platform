package io.yunxi.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多租户轴（A-03）配置。
 *
 * <p>默认 {@code enabled=false}：装饰器 pass-through，保证存量数据（无租户前缀）零迁移、行为不变。
 * 启用前须完成存量数据迁移至 {@code defaultTenant} 命名空间，否则新旧键空间不互通。
 */
@ConfigurationProperties(prefix = "yunxi.tenant")
public class TenantProperties {

    /** 是否启用多租户键空间隔离。 */
    private boolean enabled = false;

    /** 未解析到租户时的回落租户（单租户回落 / 存量默认命名空间）。 */
    private String defaultTenant = "default";

    /** 承载租户标识的入站 HTTP 头名（由 {@code HeaderTenantResolver} 读取）。 */
    private String headerName = "X-Tenant-Id";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }
}
