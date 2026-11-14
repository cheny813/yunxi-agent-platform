package io.yunxi.app;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.config.TenantAgentAllocation;
import io.yunxi.definition.Tenant;
import io.yunxi.definition.TenantStatus;
import io.yunxi.runtime.NoSuchAgentException;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import io.yunxi.runtime.tenant.TenantNotServiceableException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 固化 AG-UI 协议租户授权：YunxiGuardedAguiAgentAdapter.run 入口从 RuntimeContext 取出 tenant 后，
 * 经 TenantLifecycleService.admit 在入口拒绝 SUSPENDED/ARCHIVED 租户（fail-closed），
 * 再经 assertAllowed 越权拦截（租户随 RuntimeContext 数据传递，线程安全，不依赖 ThreadLocal 解析）。
 */
class YunxiGuardedAguiAgentAdapterTest {

    @Test
    void runRejectsWhenTenantNotAllowed() {
        Agent agent = Mockito.mock(Agent.class);
        Mockito.when(agent.getAgentId()).thenReturn("a2");
        AguiAdapterConfig config = Mockito.mock(AguiAdapterConfig.class);
        SessionTurnGate gate = Mockito.mock(SessionTurnGate.class);

        InMemoryConfigCenter configCenter = new InMemoryConfigCenter();
        configCenter.registerTenantAllocation(new TenantAgentAllocation(Map.of("t1", Set.of("a1")), Set.of()));
        TenantAgentAllocationService allocation = new TenantAgentAllocationService(configCenter);

        YunxiGuardedAguiAgentAdapter adapter =
                new YunxiGuardedAguiAgentAdapter(agent, config, gate, allocation,
                        new TenantLifecycleService(configCenter));

        RuntimeContext ctx = RuntimeContext.builder().userId("u1").put("tenant", "t1").build();
        // 租户 t1 仅允许 a1；AG-UI run 入口取出 RuntimeContext.tenant 后 assertAllowed 应拦截 a2。
        assertThrows(NoSuchAgentException.class, () -> adapter.run(null, ctx));
    }

    @Test
    void runRejectsWhenTenantSuspended() {
        Agent agent = Mockito.mock(Agent.class);
        Mockito.when(agent.getAgentId()).thenReturn("a2");
        AguiAdapterConfig config = Mockito.mock(AguiAdapterConfig.class);
        SessionTurnGate gate = Mockito.mock(SessionTurnGate.class);

        InMemoryConfigCenter configCenter = new InMemoryConfigCenter();
        configCenter.registerTenant(Tenant.active("s1", "S").withStatus(TenantStatus.SUSPENDED));
        TenantAgentAllocationService allocation = new TenantAgentAllocationService(configCenter);

        YunxiGuardedAguiAgentAdapter adapter =
                new YunxiGuardedAguiAgentAdapter(agent, config, gate, allocation,
                        new TenantLifecycleService(configCenter));

        RuntimeContext ctx = RuntimeContext.builder().userId("u1").put("tenant", "s1").build();
        // SUSPENDED 租户在 run 入口被 admit 拒绝（fail-closed），不进入 assertAllowed。
        assertThrows(TenantNotServiceableException.class, () -> adapter.run(null, ctx));
    }
}
