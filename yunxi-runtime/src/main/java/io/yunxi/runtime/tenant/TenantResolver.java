package io.yunxi.runtime.tenant;

import java.util.Map;

/**
 * 租户维度的唯一解析点。
 *
 * <p>从入站请求上下文（此处以请求头 {@code Map} 表示，ingress 子步将由其实际 inbound 形态构建）解析
 * tenantId；返回 {@code null} 表示未解析（由装饰器回落 {@link TenantConfig#defaultTenant()}）。
 *
 * <p>全仓只允许这一个地方解析租户，杜绝各地散落解析逻辑。
 */
@FunctionalInterface
public interface TenantResolver {

    /** 解析 tenantId；未解析返回 {@code null}。 */
    String resolve(Map<String, String> inboundHeaders);
}
