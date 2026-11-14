package io.yunxi.fabric;

import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.yunxi.fabric.InMemoryBaseStore;
import io.yunxi.runtime.DistributedRuntimeConfig;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 平台织面。单机、非持久化的分布式运行时后端实现（兜底 / 测试 / 演示用）。
 *
 * <p>不装配任何分布式存储；AgentScope-Java 退化为进程内内存态，正确性由本地 CAS 保证，无跨副本互斥。
 * 但单机进程内仍需防止同一会话的并发重入，故 {@link #sessionTurnGate()} 返回进程内公平信号量，
 * 与 {@link RedisDistributedRuntimeConfig} 在「同会话串行化」语义上保持一致，只是作用域限于单 JVM。
 */
public class InMemoryDistributedRuntimeConfig implements DistributedRuntimeConfig {

    private static final SessionTurnGate IN_PROCESS_GATE = new LocalSessionTurnGate();

    /**
     * 进程内沙箱执行槽表（按隔离键串行化）。提升为实例字段，保证同一运行时配置实例返回的守卫共享同一张表，
     * 从而使单机内跨 Agent 的 AGENT/GLOBAL 作用域隔离键仍能互斥（AgentConfigurer 每构建 1 个 Agent 即调用一次
     * sandboxExecutionGuard()；若每次调用新建 map，跨 Agent 串行化即失效，重现 last-write-wins 风险）。
     */
    private final ConcurrentHashMap<SandboxIsolationKey, Semaphore> slots = new ConcurrentHashMap<>();

    /** 不装配分布式存储（单机内存态，无跨副本持久化）。 */
    @Override
    public DistributedStore distributedStore() {
        return null;
    }

    /** 返回进程内公平信号量闸门，保证单机同会话串行化（不跨 JVM）。 */
    @Override
    public SessionTurnGate sessionTurnGate() {
        return IN_PROCESS_GATE;
    }

    /** 单机以内存 BaseStore 承载 RAG 分块（与分布式 Redis 实现共用同一抽象）。 */
    @Override
    public BaseStore sharedChunkStore() {
        return new InMemoryBaseStore();
    }

    /**
     * 返回进程内沙箱执行守卫：单 JVM 内按隔离键串行化，语义与 Redis 版跨实例互斥一致，作用域限于单 JVM。
     */
    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return key -> {
            Semaphore sem = slots.computeIfAbsent(key, k -> new Semaphore(1));
            sem.acquire();
            return new SandboxLease() {
                @Override
                public void close() {
                    sem.release();
                }
            };
        };
    }
}
