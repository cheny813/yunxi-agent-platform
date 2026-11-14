package io.yunxi.runtime.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link HeaderTenantResolver} 的解析验证：按配置头名从入站头映射提取租户。
 *
 * <p>守卫「全仓唯一租户解析点」语义：头存在即返回其值；头缺失或入参为 {@code null} 时返回 {@code null}，
 * 由装饰器回落 {@link TenantConfig#defaultTenant()}。
 */
class TenantResolverTest {

    /** 配置头存在时返回其原始值，忽略其它无关头。 */
    @Test
    void resolvesFromConfiguredHeader() {
        TenantResolver resolver = new HeaderTenantResolver("X-Tenant-Id");
        assertEquals("t1", resolver.resolve(Map.of("X-Tenant-Id", "t1", "Other", "x")));
    }

    /** 配置头缺失时返回 null（触发默认租户回落）。 */
    @Test
    void returnsNullWhenHeaderAbsent() {
        TenantResolver resolver = new HeaderTenantResolver("X-Tenant-Id");
        assertNull(resolver.resolve(Map.of("Other", "x")));
    }

    /** 入站头映射为 null 时不抛异常，返回 null。 */
    @Test
    void returnsNullWhenHeadersNull() {
        TenantResolver resolver = new HeaderTenantResolver("X-Tenant-Id");
        assertNull(resolver.resolve(null));
    }

    /**
     * 头名大小写不敏感：请求头为全小写 {@code x-tenant-id} 时，仍能按配置 {@code X-Tenant-Id} 解析。
     *
     * <p>守卫 {@link HeaderTenantResolver} 修复的语义——嵌入式 Tomcat 在 {@code getHeaderNames()}
     * 枚举时常把头名小写化，精确匹配会导致默认 {@code X-Tenant-Id} 永远 miss；此处把该修复钉死在单元层，
     * 避免回退为精确匹配时仅集成测试（需真实容器）才能发现。</p>
     */
    @Test
    void resolvesCaseInsensitively() {
        TenantResolver resolver = new HeaderTenantResolver("X-Tenant-Id");
        assertEquals("t1", resolver.resolve(Map.of("x-tenant-id", "t1", "Other", "x")));
    }

    /** 头名大小写不敏感：请求头为混合大小写 {@code x-TENANT-id} 时亦能解析。 */
    @Test
    void resolvesWithMixedCaseHeader() {
        TenantResolver resolver = new HeaderTenantResolver("X-Tenant-Id");
        assertEquals("t1", resolver.resolve(Map.of("x-TENANT-id", "t1")));
    }
}
