package io.yunxi.platform.aistio.orchestration.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.yunxi.platform.aistio.orchestration.OrchestrationTrigger;
import io.yunxi.platform.aistio.orchestration.TriggerReceipt;
import reactor.core.publisher.Mono;

class WorkflowToolTest {

    @Test
    void startTriggersAndRecordsLocalState() {
        OrchestrationTrigger trigger = mock(OrchestrationTrigger.class);
        WorkflowRunStateStore store = new WorkflowRunStateStore.InMemory();
        when(trigger.start(eq("intake-v1"), any(), any()))
                .thenReturn(Mono.just(new TriggerReceipt("d1", "run-1", "queued", "corr-1")));

        WorkflowTool tool = new WorkflowTool("intake-v1", trigger, store);
        Map<String, Object> result = tool.start(Map.of("k", "v"), "biz-1");

        assertThat(result.get("status")).isEqualTo("queued");
        assertThat(result.get("automationRunId")).isEqualTo("run-1");
        assertThat(result.get("correlationId")).isEqualTo("corr-1");
        // 触发侧左移写入本地状态，status 后续可查
        assertThat(store.get("corr-1").phase()).isEqualTo("triggered");
    }

    @Test
    void statusReturnsUnknownWhenAbsent() {
        OrchestrationTrigger trigger = mock(OrchestrationTrigger.class);
        WorkflowRunStateStore store = new WorkflowRunStateStore.InMemory();
        WorkflowTool tool = new WorkflowTool("intake-v1", trigger, store);

        Map<String, Object> result = tool.status("nope");
        assertThat(result.get("status")).isEqualTo("unknown");
    }

    @Test
    void cancelMarksCancellationRequested() {
        OrchestrationTrigger trigger = mock(OrchestrationTrigger.class);
        WorkflowRunStateStore store = new WorkflowRunStateStore.InMemory();
        WorkflowTool tool = new WorkflowTool("intake-v1", trigger, store);

        Map<String, Object> result = tool.cancel("corr-x");
        assertThat(result.get("status")).isEqualTo("cancellation_requested");
        assertThat(store.get("corr-x").phase()).isEqualTo("cancellation_requested");
    }
}
