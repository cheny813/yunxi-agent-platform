package io.yunxi.runtime;

import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;

/**
 * 运行时平面。{@link AgentConfigurer} 在装配期所需的分布式运行时后端契约：{@link DistributedStore}
 * （状态持久化 + 跨实例 CAS）与可选的 {@link SessionTurnGate}（跨实例轮次串行化）。
 *
 * <p>该接口放在运行时平面（即消费方），因此与提供具体实现的织面平面之间没有模块循环依赖；
 * 织面平面负责提供具体实现（内存版 / Redis 版）。接口应放在它被消费的位置。
 */
public interface DistributedRuntimeConfig {

    /** Distributed store for agent state. May be {@code null} for a single-instance, non-persistent deployment. */
    DistributedStore distributedStore();

    /**
     * 跨实例轮次闸门。框架的 {@code DistributedStore.sessionTurnGate()} 默认返回 {@code null}（仅进程内）；
     * 需要跨实例互斥的部署在此处装配一个非空的闸门。当非空时，框架要求配套 {@code ConflictPolicy.FAIL} 以保证正确性。
     */
    default SessionTurnGate sessionTurnGate() {
        return null;
    }

    /**
     * 返回 RAG 分块存储后端（框架共享存储抽象 {@link BaseStore}）。
     * 分布式部署为框架 Redis 实现，单机为内存实现；非空保证使 RAG 检索平面在任意部署形态下可存续。
     */
    BaseStore sharedChunkStore();

    /**
     * 沙箱执行并发守卫：跨实例互斥同一隔离键的执行槽，防止多副本 last-write-wins 覆盖。
     * 默认返回 {@code SandboxExecutionGuard.noop()}（无跨实例协调）；具体实现按部署形态覆盖：
     * cluster 模式返回基于全局 Redis 的分布式锁，standalone 返回进程内信号量。
     */
    default SandboxExecutionGuard sandboxExecutionGuard() {
        return SandboxExecutionGuard.noop();
    }
}
