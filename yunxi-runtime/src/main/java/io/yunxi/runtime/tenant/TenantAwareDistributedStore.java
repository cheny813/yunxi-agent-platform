package io.yunxi.runtime.tenant;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.yunxi.definition.TenantStorageMeter;

/**
 * 框架 {@link DistributedStore} 的租户感知装饰器（A-03 多租户轴的单一装配产物）。
 *
 * <p>仅对 {@link #agentStateStore()} / {@link #baseStore()} 做键空间隔离（前置 {@code tenant:{tid}}）；
 * 其余组件（sandbox 快照 / 执行护栏 / 会话轮换闸门）与租户键空间无关，直接委托透传，保持 YAGNI。
 *
 * <p>装配点唯一：仅由 {@code AgentConfigurer.configure} 在 {@code enabled=true} 时包裹，
 * {@code enabled=false} 时 {@code AgentConfigurer} 不包裹 → 返回的仍是原始 store 实例（向后兼容）。
 * 透传的 {@link TenantStorageMeter} 在键空间边界做近似存储计量（fail-open，缺失则静默跳过）。
 */
public final class TenantAwareDistributedStore implements DistributedStore {

    private final DistributedStore delegate;
    private final TenantConfig config;
    private final TenantStorageMeter meter;

    public TenantAwareDistributedStore(DistributedStore delegate, TenantConfig config, TenantStorageMeter meter) {
        this.delegate = delegate;
        this.config = config;
        this.meter = meter;
    }

    @Override
    public AgentStateStore agentStateStore() {
        return new TenantAwareAgentStateStore(delegate.agentStateStore(), config, meter);
    }

    @Override
    public BaseStore baseStore() {
        return new TenantAwareBaseStore(delegate.baseStore(), config, meter);
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return delegate.sandboxSnapshotSpec();
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return delegate.sandboxExecutionGuard();
    }

    @Override
    public SessionTurnGate sessionTurnGate() {
        return delegate.sessionTurnGate();
    }
}
