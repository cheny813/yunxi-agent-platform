package io.yunxi.app;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.yunxi.definition.TenantStatus;
import io.yunxi.runtime.SessionTurnGuard;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.runtime.tenant.TenantContext;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import reactor.core.publisher.Flux;

/**
 * AG-UI 协议入口的会话轮次闸门包裹器：继承框架默认 {@link AguiAgentAdapter}（保留其 AG-UI 事件转换逻辑），
 * 仅覆写 {@code run}，把真实执行包进 yunxi 的 {@link SessionTurnGuard}——与 web / Chat Completions 经
 * {@code DefaultExecutionFacade} 共用同一处取/放闸门逻辑，杜绝 AG-UI 单独实现闸门导致并发语义漂移。
 *
 * <p>轮次键约定与门面一致：{@code userId:threadId}（线程即 AG-UI 会话；userId 缺失退化为 anon，
 * threadId 缺失退化为 agent），保证同一 AG-UI 会话的并发入站被串行化，且不与其他协议的会话相互干扰。</p>
 *
 * <p>租户授权：租户由 {@link YunxiAguiRuntimeContextResolver} 解析并写入 RuntimeContext，
 * 此处取出后在 assertAllowed 越权拦截；并以 {@link TenantContext#withTenant} 绑定租户（AG-UI 异步线程模型下，
 * 租户随 RuntimeContext 数据传递，不依赖 ThreadLocal 解析，withTenant 仅兜底供应 ThreadLocal 读取）。</p>
 */
public class YunxiGuardedAguiAgentAdapter extends AguiAgentAdapter {

    private final Agent agent;
    private final SessionTurnGate gate;
    private final TenantAgentAllocationService allocation;
    private final TenantLifecycleService tenantLifecycleService;

    public YunxiGuardedAguiAgentAdapter(Agent agent, AguiAdapterConfig config, SessionTurnGate gate,
            TenantAgentAllocationService allocation, TenantLifecycleService tenantLifecycleService) {
        super(agent, config);
        this.agent = agent;
        this.gate = gate;
        this.allocation = allocation;
        this.tenantLifecycleService = tenantLifecycleService;
    }

    @Override
    public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
        String tenant = runtimeContext != null ? (String) runtimeContext.get("tenant") : null;
        // 准入闸门在入口同步拒绝非 ACTIVE 租户（null 租户 fail-open 为 ACTIVE）。
        TenantStatus status = tenantLifecycleService.admit(tenant);
        // 租户已随 RuntimeContext 数据对象传递（线程安全）；此处用局部 tenant 直接做越权拦截。
        if (tenant != null) {
            allocation.assertAllowed(tenant, agent.getAgentId());
        }
        String userId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String threadId = input != null ? input.getThreadId() : null;
        String key = (userId == null ? "anon" : userId) + ":" + (threadId == null ? "agent" : threadId);
        Flux<AguiEvent> inner = SessionTurnGuard.guard(super.run(input, runtimeContext), key, gate);
        // 绑定租户到 TenantContext：withTenant 对 null 租户透传，已解析租户则供应 ThreadLocal。
        return TenantContext.withTenant(inner, tenant, status);
    }
}
