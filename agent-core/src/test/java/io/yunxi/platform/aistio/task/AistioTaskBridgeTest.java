package io.yunxi.platform.aistio.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.agentscope.extensions.aistio.adapter.AgentTaskStarter;
import io.agentscope.extensions.aistio.adapter.HarnessAgentTaskStarter;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.yunxi.platform.aistio.task.AistioTaskBridge.OrchestrationCorrelation;
import reactor.core.publisher.Mono;

class AistioTaskBridgeTest {

    private static AgentTaskAssignment assignment(String taskId, String attemptId, String payload) {
        return new AgentTaskAssignment(
                attemptId, taskId, "run-1", "node-1", 1L, "start", "https://ctx", "taskToken", "attemptToken",
                "session-1", payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());
    }

    @Test
    void delegatesToFrameworkStarter() {
        AtomicInteger calls = new AtomicInteger();
        AistioTaskBridge bridge = new AistioTaskBridge(a -> {
            calls.incrementAndGet();
            return Mono.empty();
        });

        bridge.start(assignment("task-1", "attempt-1", "{\"input\":\"x\"}")).block();

        assertEquals(1, calls.get());
        assertNull(bridge.correlationOf("task-1"), "任务结束后 correlation 索引应释放");
    }

    @Test
    void rejectsNewAttemptAfterStopAccepting() {
        AtomicInteger calls = new AtomicInteger();
        AistioTaskBridge bridge = new AistioTaskBridge(a -> {
            calls.incrementAndGet();
            return Mono.empty();
        });

        bridge.stopAccepting();
        assertFalse(bridge.isAccepting());
        assertThrows(IllegalStateException.class,
                () -> bridge.start(assignment("task-2", "attempt-2", null)).block());
        assertEquals(0, calls.get(), "下线后不得再委派给框架执行器");
    }

    @Test
    void readsCorrelationIdFromYunxiSegment() {
        AistioTaskBridge bridge = new AistioTaskBridge(a -> Mono.empty());
        String payload = "{\"input\":\"x\",\"_yunxi\":{\"correlationId\":\"corr-abc\"}}";

        // 不订阅：Mono 惰性，保持在跑状态（真实场景下由框架订阅，完成后 doFinally 才移除索引）
        bridge.start(assignment("task-3", "attempt-3", payload));

        OrchestrationCorrelation correlation = bridge.correlationOf("task-3");
        assertNotNull(correlation);
        assertEquals("corr-abc", correlation.correlationId());
        assertFalse(correlation.degraded());
        assertEquals("run-1", correlation.runId());
        assertEquals("node-1", correlation.nodeId());
        assertEquals("attempt-3", correlation.attemptId());
    }

    @Test
    void marksDegradedWhenConsoleTriggeredManually() {
        AistioTaskBridge bridge = new AistioTaskBridge(a -> Mono.empty());

        bridge.start(assignment("task-4", "attempt-4", "{\"input\":\"x\"}"));

        OrchestrationCorrelation correlation = bridge.correlationOf("task-4");
        assertNotNull(correlation);
        assertTrue(correlation.degraded(), "控制台人工触发无 _yunxi 段 → degraded");
        assertNull(correlation.correlationId());
        assertFalse(bridge.isAccepting() == false);
    }

    @Test
    void toleratesNonJsonPayload() {
        AistioTaskBridge bridge = new AistioTaskBridge(a -> Mono.empty());

        bridge.start(assignment("task-5", "attempt-5", "not-json"));

        OrchestrationCorrelation correlation = bridge.correlationOf("task-5");
        assertNotNull(correlation);
        assertTrue(correlation.degraded());
    }
}
