package io.yunxi.platform.execution.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.CustomEvent;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.ExecutionRequest;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * PhaseTracker（order=700）：阶段推导状态机测试。
 */
@DisplayName("PhaseTracker 阶段推导")
class PhaseTrackerTest {

    private final PhaseTracker tracker = new PhaseTracker();

    private ExecutionContext ctx() {
        ExecutionRequest request = ExecutionRequest.builder().message("hi").agentName("agent-a").build();
        return new ExecutionContext(request, null, null, "agent-a");
    }

    private AgentEvent event(AgentEventType type) {
        AgentEvent e = mock(AgentEvent.class);
        when(e.getType()).thenReturn(type);
        return e;
    }

    private boolean isStatus(AgentEvent e, String phase) {
        return e instanceof CustomEvent ce
                && PhaseTracker.AGENT_STATUS_EVENT_NAME.equals(ce.getName())
                && phase.equals(ce.getValue().get("phase"));
    }

    @Test
    @DisplayName("单阶段事件：切换注入标记 + 完成注入 DONE")
    void singlePhaseSwitch() {
        AgentEvent thinking = event(AgentEventType.MODEL_CALL_START);
        ExecutionContext ctx = ctx();

        Flux<AgentEvent> result = tracker.apply(Flux.just(thinking), ctx);

        StepVerifier.create(result)
                .assertNext(e -> assertThat(isStatus(e, "THINKING")).isTrue())
                .expectNext(thinking)
                .assertNext(e -> assertThat(isStatus(e, "DONE")).isTrue())
                .verifyComplete();

        // 轨迹与最近阶段写入
        assertThat(ctx.<List<String>>getAttribute(PhaseTracker.ATTR_PHASE_TRACE))
                .containsExactly("IDLE", "THINKING", "DONE");
        assertThat(ctx.<String>getAttribute(PhaseTracker.ATTR_LAST_PHASE)).isEqualTo("DONE");
        PhaseTracker.PhaseTrace last = tracker.getLastPhaseTrace("agent-a");
        assertThat(last).isNotNull();
        assertThat(last.trace()).containsExactly("IDLE", "THINKING", "DONE");
        assertThat(last.timestamp()).isPositive();
    }

    @Test
    @DisplayName("多阶段切换：THINKING → ANSWER → DONE")
    void multiPhaseSwitch() {
        AgentEvent thinking = event(AgentEventType.MODEL_CALL_START);
        AgentEvent answering = event(AgentEventType.TEXT_BLOCK_START);
        ExecutionContext ctx = ctx();

        Flux<AgentEvent> result = tracker.apply(Flux.just(thinking, answering), ctx);

        StepVerifier.create(result)
                .assertNext(e -> assertThat(isStatus(e, "THINKING")).isTrue())
                .expectNext(thinking)
                .assertNext(e -> assertThat(isStatus(e, "ANSWER")).isTrue())
                .expectNext(answering)
                .assertNext(e -> assertThat(isStatus(e, "DONE")).isTrue())
                .verifyComplete();

        assertThat(ctx.<List<String>>getAttribute(PhaseTracker.ATTR_PHASE_TRACE))
                .containsExactly("IDLE", "THINKING", "ANSWER", "DONE");
    }

    @Test
    @DisplayName("同阶段事件不重复注入")
    void noDuplicateSwitch() {
        AgentEvent e1 = event(AgentEventType.MODEL_CALL_START);
        AgentEvent e2 = event(AgentEventType.THINKING_BLOCK_START);
        ExecutionContext ctx = ctx();

        Flux<AgentEvent> result = tracker.apply(Flux.just(e1, e2), ctx);

        StepVerifier.create(result)
                .assertNext(e -> assertThat(isStatus(e, "THINKING")).isTrue())
                .expectNext(e1)
                .expectNext(e2)
                .assertNext(e -> assertThat(isStatus(e, "DONE")).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("未映射事件（如 CUSTOM）透传，不注入阶段标记")
    void unmappedEventPassThrough() {
        AgentEvent custom = event(AgentEventType.CUSTOM);
        ExecutionContext ctx = ctx();

        Flux<AgentEvent> result = tracker.apply(Flux.just(custom), ctx);

        StepVerifier.create(result)
                .expectNext(custom)
                .assertNext(e -> assertThat(isStatus(e, "DONE")).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("流异常：注入 ERROR 标记后重抛，轨迹记录 ERROR")
    void errorInjectsErrorPhase() {
        RuntimeException boom = new RuntimeException("模型调用失败");
        ExecutionContext ctx = ctx();

        Flux<AgentEvent> result = tracker.apply(Flux.error(boom), ctx);

        StepVerifier.create(result)
                .assertNext(e -> assertThat(isStatus(e, "ERROR")).isTrue())
                .expectErrorSatisfies(t -> assertThat(t).isSameAs(boom))
                .verify();

        assertThat(ctx.<String>getAttribute(PhaseTracker.ATTR_LAST_PHASE)).isEqualTo("ERROR");
        assertThat(ctx.<List<String>>getAttribute(PhaseTracker.ATTR_PHASE_TRACE))
                .containsExactly("IDLE", "ERROR");
    }
}
