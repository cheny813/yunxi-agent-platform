package io.yunxi.app;

import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.config.TenantAgentAllocation;
import io.yunxi.definition.Tenant;
import io.yunxi.definition.TenantStatus;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.runtime.tenant.HeaderTenantResolver;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import io.yunxi.runtime.tenant.TenantNotServiceableException;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 固化 A2A 协议租户授权：A2aController 在 registry.get 之前经 TenantAgentAllocationService.assertAllowed 拦截越权，
 * 并经 TenantLifecycleService.admit 在入口拒绝 SUSPENDED/ARCHIVED 租户（fail-closed）；
 * 租户由 servlet 线程经 TenantContext.set 绑定（try/finally 兜底清理）。
 */
class A2aControllerTest {

    @Test
    void cardRejectsWhenTenantNotAllowed() {
        A2aServerRegistry registry = Mockito.mock(A2aServerRegistry.class);
        InMemoryConfigCenter config = new InMemoryConfigCenter();
        config.registerTenantAllocation(new TenantAgentAllocation(Map.of("t1", Set.of("a1")), Set.of()));
        A2aController controller = new A2aController(registry, new TenantAgentAllocationService(config),
                new HeaderTenantResolver("X-Tenant-Id"), TenantConfig.DISABLED,
                new TenantLifecycleService(config), Mockito.mock(ObjectProvider.class));

        // 越权租户 t1 仅允许 a1；请求 example-assistant 应在 TenantContext.set 后、registry.get 前被 assertAllowed 拒绝。
        assertThrows(io.yunxi.runtime.NoSuchAgentException.class,
                () -> controller.cardWellKnown("example-assistant", Map.of("X-Tenant-Id", "t1")));
    }

    @Test
    void cardRejectsWhenTenantSuspended() {
        A2aServerRegistry registry = Mockito.mock(A2aServerRegistry.class);
        InMemoryConfigCenter config = new InMemoryConfigCenter();
        config.registerTenant(Tenant.active("s1", "S").withStatus(TenantStatus.SUSPENDED));
        A2aController controller = new A2aController(registry, new TenantAgentAllocationService(config),
                new HeaderTenantResolver("X-Tenant-Id"), TenantConfig.DISABLED,
                new TenantLifecycleService(config), Mockito.mock(ObjectProvider.class));

        // SUSPENDED 租户在 resolveTenant 内被 admit 拒绝（fail-closed），不进入 assertAllowed / registry.get。
        assertThrows(TenantNotServiceableException.class,
                () -> controller.cardWellKnown("example-assistant", Map.of("X-Tenant-Id", "s1")));
    }
}
