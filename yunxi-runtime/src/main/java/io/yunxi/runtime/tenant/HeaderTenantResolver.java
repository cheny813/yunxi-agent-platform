package io.yunxi.runtime.tenant;

import java.util.Locale;
import java.util.Map;

/**
 * 基于请求头的 {@link TenantResolver} 默认实现。
 *
 * <p>从指定请求头读取 tenantId；头缺失或入参为 {@code null} 时返回 {@code null}（回落默认租户）。
 *
 * <p>匹配对头名大小写不敏感：HTTP 头字段名按 RFC 7230 大小写不敏感，且嵌入式 Servlet 容器
 * （如 Tomcat）在 {@code getHeaderNames()} 枚举时常把头名小写化，精确匹配会导致默认
 * {@code X-Tenant-Id} 永远命中不到，故此处统一按小写比较。</p>
 */
public final class HeaderTenantResolver implements TenantResolver {

    private final String headerName;

    public HeaderTenantResolver(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public String resolve(Map<String, String> inboundHeaders) {
        if (inboundHeaders == null || inboundHeaders.isEmpty()) {
            return null;
        }
        String target = headerName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : inboundHeaders.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(target)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
