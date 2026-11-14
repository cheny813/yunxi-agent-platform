package io.yunxi.app;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.yunxi.runtime.DistributedRuntimeConfig;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import org.springframework.stereotype.Component;

/**
 * 覆盖框架默认的 {@link AguiAgentAdapterFactory}（框架以 {@code @ConditionalOnMissingBean} 暴露此扩展点）：
 * 让 AG-UI 入口的执行路径经 {@link YunxiGuardedAguiAgentAdapter} 复用 yunxi 的会话轮次闸门，
 * 与 web / Chat Completions 收敛到同一并发正确性模型。身份解析仍由 {@code YunxiAguiRuntimeContextResolver}
 * 经 {@link io.yunxi.runtime.ProtocolUserIdResolver} 完成（同一单一真相源）。
 */
@Component
public class YunxiAguiAgentAdapterFactory implements AguiAgentAdapterFactory {

    private final SessionTurnGate gate;
    private final TenantAgentAllocationService allocation;
    private final TenantLifecycleService tenantLifecycleService;

    /** 注入分布式运行时后端的轮次闸门（单机内存后端时为 null，由 SessionTurnGuard 透传）与租户分配/生命周期服务。 */
    public YunxiAguiAgentAdapterFactory(DistributedRuntimeConfig distributedRuntimeConfig,
            TenantAgentAllocationService allocation, TenantLifecycleService tenantLifecycleService) {
        this.gate = distributedRuntimeConfig.sessionTurnGate();
        this.allocation = allocation;
        this.tenantLifecycleService = tenantLifecycleService;
    }

    @Override
    public AguiAgentAdapter create(Agent agent, AguiAdapterConfig config) {
        return new YunxiGuardedAguiAgentAdapter(agent, config, gate, allocation, tenantLifecycleService);
    }
}
