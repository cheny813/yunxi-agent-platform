package io.yunxi.runtime;

import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.config.TenantAgentAllocation;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 固化 web 路径租户授权：DefaultExecutionFacade.execute 在 gateway.findAgent 之前经
 * TenantAgentAllocationService.assertAllowed 拦截越权；租户由入口经 TenantContext.withTenant 于订阅线程绑定，
 * 故 execute 整体须延迟到订阅后（Flux.defer）才读取 current()，否则读到 null。
 */
class DefaultExecutionFacadeTest {

    @Test
    void executeRejectsWhenTenantNotAllowed() {
        InMemoryConfigCenter config = new InMemoryConfigCenter();
        config.registerTenantAllocation(new TenantAgentAllocation(Map.of("t1", Set.of("a1")), Set.of()));
        TenantAgentAllocationService service = new TenantAgentAllocationService(config);
        HarnessGateway gateway = Mockito.mock(HarnessGateway.class);
        DefaultExecutionFacade facade = new DefaultExecutionFacade(gateway, service);

        AgentCommand command = new AgentCommand(
                "a2", null, "u1", "s1", List.of(), Map.of(), null, Map.of(), null, null, null);

        // 租户由入口（IngressGateway / Chat Completions）经 withTenant 于订阅线程绑定，execute 内读取。
        // blockFirst 触发订阅：doFirst 设置 ThreadLocal → Flux.defer 执行 assertAllowed(t1, a2) → 越权抛 NoSuchAgentException。
        assertThrows(NoSuchAgentException.class,
                () -> TenantContext.withTenant(facade.execute(command), "t1").blockFirst());
    }
}
