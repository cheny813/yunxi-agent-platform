package io.yunxi.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * 验证 standalone（InMemory）后端提供的进程内沙箱执行守卫：同一隔离键固定 permits=1 串行化，
 * 与 Redis 版跨实例互斥语义一致，作用域限于单 JVM。
 */
class InMemoryDistributedRuntimeConfigTest {

    @Test
    void sandboxGuard_serializesAtOnePermit() throws Exception {
        SandboxExecutionGuard guard = new InMemoryDistributedRuntimeConfig().sandboxExecutionGuard();
        SandboxIsolationKey key = SandboxIsolationKey.resolve(IsolationScope.AGENT, null, "agent-x").get();
        SandboxLease lease1 = guard.tryEnter(key);
        AtomicBoolean secondEntered = new AtomicBoolean(false);
        Thread blocker = new Thread(() -> {
            try {
                SandboxLease lease2 = guard.tryEnter(key);
                secondEntered.set(true);
                lease2.close();
            } catch (InterruptedException ignored) {
            }
        });
        blocker.start();
        Thread.sleep(300);
        assertFalse(secondEntered.get(), "进程内守卫固定 permits=1 时第二个调用必须阻塞");
        lease1.close();
        blocker.join(2000);
        assertTrue(secondEntered.get(), "释放许可后第二个调用必须进入");
    }

    @Test
    void distinctGuardsFromSameConfigShareSlotMap_crossAgentSerialization() throws Exception {
        // W3 回归：AgentConfigurer 每构建 1 个 Agent 即调用一次 sandboxExecutionGuard()，
        // 若信号量表是方法内新建，则跨 Agent 守卫不共享 → 跨 Agent 串行化失效。此处断言同一配置实例返回的
        // 两个独立守卫对同一隔离键仍互斥（表已提升为实例字段）。
        InMemoryDistributedRuntimeConfig config = new InMemoryDistributedRuntimeConfig();
        SandboxExecutionGuard guardA = config.sandboxExecutionGuard();
        SandboxExecutionGuard guardB = config.sandboxExecutionGuard();
        SandboxIsolationKey key = SandboxIsolationKey.resolve(IsolationScope.GLOBAL, null, "shared").get();
        SandboxLease lease = guardA.tryEnter(key);
        AtomicBoolean entered = new AtomicBoolean(false);
        Thread blocker = new Thread(() -> {
            try {
                guardB.tryEnter(key).close();
                entered.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        blocker.start();
        Thread.sleep(300);
        assertFalse(entered.get(), "同一配置实例的两个守卫须共享同一槽表，GLOBAL 键跨 Agent 串行化");
        lease.close();
        blocker.join(2000);
        assertTrue(entered.get(), "释放许可后 guardB 必须进入");
    }
}
