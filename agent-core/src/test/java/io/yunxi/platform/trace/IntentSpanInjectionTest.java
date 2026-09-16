package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.AgentInput;
import reactor.core.publisher.Flux;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 意图节点归集测试：验证意图产物成为轨迹树节点。
 *
 * <p>M3 改造后，意图节点由模型侧 intent_classify 工具调用产生（其 TOOL_CALL 事件经
 * {@code TraceComposer} 重分类为 SpanKind.INTENT，天然携带 toolCallId），
 * 不再由运行时合成注入。本类对应三条验收要求：
 * ① 意图节点与回合节点同层，不制造第二个 root；
 * ② 意图节点携带其所属工具的 toolCallId，可与工具调用节点配对；
 * ③ 全流程只产生一个回合起始事件对应的 root。</p>
 */
@DisplayName("意图节点归集")
class IntentSpanInjectionTest {

    private InMemoryTraceStore store;
    private TraceCollectorMiddleware middleware;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore();
        middleware = new TraceCollectorMiddleware(new TraceComposer(), store);
    }

    private static Agent agentNamed(String name) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(name);
        return agent;
    }

    /** 跑一次 onAgent（意图来自 intent_classify 工具调用，而非合成注入）。 */
    private void run(Flux<AgentEvent> events) {
        RuntimeContext.Builder b = RuntimeContext.builder().userId("u1").sessionId("s1");
        java.util.function.Function<AgentInput, Flux<AgentEvent>> next = input -> events;
        middleware.onAgent(agentNamed("agent-a"), b.build(), new AgentInput(List.of()), next)
                .collectList()
                .block();
    }

    private static Flux<AgentEvent> turnWithIntentTool() {
        return Flux.just(
                new AgentStartEvent("s1", "r1", "agent-a"),
                new ToolCallStartEvent("r1", "call-intent", "intent_classify"),
                new ToolCallEndEvent("r1", "call-intent", "intent_classify"),
                new ToolResultEndEvent("r1", "call-intent", "intent_classify", ToolResultState.SUCCESS),
                new AgentEndEvent("r1"));
    }

    private static Flux<AgentEvent> turnWithoutIntentTool() {
        return Flux.just(
                new AgentStartEvent("s1", "r1", "agent-a"),
                new ToolCallStartEvent("r1", "call-1", "database_query"),
                new ToolCallEndEvent("r1", "call-1", "database_query"),
                new ToolResultEndEvent("r1", "call-1", "database_query", ToolResultState.SUCCESS),
                new AgentEndEvent("r1"));
    }

    private List<ReasoningSpan> traces() {
        return store.read("u1:s1");
    }

    @Test
    @DisplayName("验收 ①：意图节点入树，且与回合节点同层")
    void intentSpanLandsOnTree() {
        run(turnWithIntentTool());

        List<ReasoningSpan> intents = traces().stream()
                .filter(s -> s.kind() == SpanKind.INTENT)
                .toList();

        assertThat(intents).as("意图工具调用应产生意图节点").isNotEmpty();

        ReasoningSpan open = intents.stream().filter(ReasoningSpan::isOpen).findFirst().orElseThrow();
        ReasoningSpan turnOpen = traces().stream()
                .filter(s -> s.kind() == SpanKind.TURN && s.isOpen())
                .findFirst().orElseThrow();

        assertThat(open.parentKey())
                .as("意图节点应挂在回合节点之下，与回合同层而非游离")
                .isEqualTo(turnOpen.stableKey());
    }

    @Test
    @DisplayName("验收 ②：意图节点携带其所属工具的 toolCallId，可与工具调用节点配对")
    void intentSpanCarriesToolCallId() {
        run(turnWithIntentTool());

        ReasoningSpan open = traces().stream()
                .filter(s -> s.kind() == SpanKind.INTENT && s.isOpen())
                .findFirst().orElseThrow();

        assertThat(open.payload())
                .as("意图工具节点应携带 toolCallId，满足验收项②（INTENT span 与工具 toolCallId 配对）")
                .containsEntry("toolCallId", "call-intent");
    }

    @Test
    @DisplayName("验收 ③：意图不制造第二个 root —— 全流程只有一个回合起始节点")
    void intentDoesNotCreateSecondRoot() {
        run(turnWithIntentTool());

        assertThat(traces().stream()
                .filter(s -> s.kind() == SpanKind.TURN && s.isOpen())
                .count())
                .as("一个用户回合只应有一个回合节点 —— 意图若另起生命周期会破坏此约束")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("意图节点完整：开启与关闭快照均为 INTENT 且共享 toolCallId")
    void intentSpanCarriesPayloadAndOutcome() {
        run(turnWithIntentTool());

        List<ReasoningSpan> intents = traces().stream()
                .filter(s -> s.kind() == SpanKind.INTENT)
                .toList();

        assertThat(intents.stream().filter(ReasoningSpan::isOpen)).as("意图节点应有开启快照").hasSize(1);
        assertThat(intents.stream().filter(ReasoningSpan::isClosed)).as("意图节点应有关闭快照").isNotEmpty();

        ReasoningSpan open = intents.stream().filter(ReasoningSpan::isOpen).findFirst().orElseThrow();
        ReasoningSpan closed = intents.stream().filter(ReasoningSpan::isClosed).findFirst().orElseThrow();

        assertThat(open.payload()).containsEntry("toolCallId", "call-intent");
        assertThat(closed.payload()).containsEntry("toolCallId", "call-intent");
    }

    @Test
    @DisplayName("未调用意图工具时不产生意图节点")
    void noIntentToolProducesNoIntentSpan() {
        run(turnWithoutIntentTool());

        assertThat(traces().stream().filter(s -> s.kind() == SpanKind.INTENT))
                .as("未调用 intent_classify 即无意图节点")
                .isEmpty();
        assertThat(traces()).isNotEmpty();
    }

    @Test
    @DisplayName("意图工具节点不干扰后续节点：普通工具调用仍正确挂树")
    void injectionDoesNotBreakSubsequentNodes() {
        Flux<AgentEvent> events = Flux.just(
                new AgentStartEvent("s1", "r1", "agent-a"),
                new ToolCallStartEvent("r1", "call-intent", "intent_classify"),
                new ToolCallEndEvent("r1", "call-intent", "intent_classify"),
                new ToolResultEndEvent("r1", "call-intent", "intent_classify", ToolResultState.SUCCESS),
                new ToolCallStartEvent("r1", "call-1", "database_query"),
                new ToolCallEndEvent("r1", "call-1", "database_query"),
                new ToolResultEndEvent("r1", "call-1", "database_query", ToolResultState.SUCCESS),
                new AgentEndEvent("r1"));
        run(events);

        ReasoningSpan toolOpen = traces().stream()
                .filter(s -> s.kind() == SpanKind.TOOL_CALL && s.isOpen())
                .findFirst().orElseThrow();

        assertThat(toolOpen.parentKey())
                .as("意图工具节点不入栈，普通工具的父节点仍应是回合节点")
                .isNotNull();
        assertThat(toolOpen.payload())
                .as("普通工具调用标识应可直接读取")
                .containsEntry("toolCallId", "call-1");
        assertThat(traces().stream()
                .filter(s -> s.kind() == SpanKind.INTENT)
                .map(ReasoningSpan::stableKey)
                .distinct()
                .count())
                .as("意图工具节点与普通工具节点应各自独立（只产生一个意图节点，不重复计数快照）")
                .isEqualTo(1);
    }
}
