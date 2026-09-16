package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.ToolResultState;

/**
 * ReasoningSpan：快照语义与工厂方法测试。
 */
@DisplayName("ReasoningSpan 快照语义")
class ReasoningSpanTest {

    private static final String TRACE = "trace-1";
    private static final String KEY = "key-1";
    private static final String EVENT = "event-1";

    @Test
    @DisplayName("开始快照：仅填充归属与种类，终态字段为空")
    void openSnapshot() {
        ReasoningSpan span = ReasoningSpan.open(TRACE, KEY, EVENT, SpanKind.TOOL_CALL, "parent-1");

        assertThat(span.traceId()).isEqualTo(TRACE);
        assertThat(span.stableKey()).isEqualTo(KEY);
        assertThat(span.eventId()).isEqualTo(EVENT);
        assertThat(span.kind()).isEqualTo(SpanKind.TOOL_CALL);
        assertThat(span.parentKey()).isEqualTo("parent-1");
        assertThat(span.snapshot()).isEqualTo(Snapshot.OPEN);
        assertThat(span.isOpen()).isTrue();
        assertThat(span.delta()).isNull();
        assertThat(span.status()).isNull();
        assertThat(span.durationMs()).isNull();
        assertThat(span.payload()).isNull();
        assertThat(span.at()).isPositive();
    }

    @Test
    @DisplayName("增量快照：携带增量片段，且与开始快照共享稳定标识")
    void deltaSnapshot() {
        ReasoningSpan span = ReasoningSpan.delta(TRACE, KEY, EVENT, SpanKind.TEXT, "片段");

        assertThat(span.snapshot()).isEqualTo(Snapshot.DELTA);
        assertThat(span.isDelta()).isTrue();
        assertThat(span.delta()).isEqualTo("片段");
        assertThat(span.stableKey()).isEqualTo(KEY);
        assertThat(span.status()).isNull();
    }

    @Test
    @DisplayName("关闭快照：填充终态与负载")
    void closedSnapshot() {
        Map<String, Object> payload = Map.of("result", "完成", "usage", 12);
        ReasoningSpan span = ReasoningSpan.closed(TRACE, KEY, EVENT, SpanKind.TOOL_CALL,
                ToolResultState.SUCCESS, 150L, payload);

        assertThat(span.snapshot()).isEqualTo(Snapshot.CLOSED);
        assertThat(span.isClosed()).isTrue();
        assertThat(span.status()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(span.durationMs()).isEqualTo(150L);
        assertThat(span.payload()).containsEntry("result", "完成");
        assertThat(span.delta()).isNull();
    }

    @Test
    @DisplayName("三种快照共享稳定标识，可据此分组还原同一节点")
    void threeSnapshotsShareStableKey() {
        ReasoningSpan open = ReasoningSpan.open(TRACE, KEY, EVENT, SpanKind.TEXT, null);
        ReasoningSpan delta = ReasoningSpan.delta(TRACE, KEY, EVENT, SpanKind.TEXT, "内容");
        ReasoningSpan closed = ReasoningSpan.closed(TRACE, KEY, EVENT, SpanKind.TEXT,
                ToolResultState.SUCCESS, 10L, null);

        assertThat(open.stableKey()).isEqualTo(delta.stableKey()).isEqualTo(closed.stableKey());
        assertThat(open.traceId()).isEqualTo(delta.traceId()).isEqualTo(closed.traceId());
        assertThat(open.snapshot()).isEqualTo(Snapshot.OPEN);
        assertThat(delta.snapshot()).isEqualTo(Snapshot.DELTA);
        assertThat(closed.snapshot()).isEqualTo(Snapshot.CLOSED);
    }

    @Test
    @DisplayName("附加阶段：返回新快照，原快照不变")
    void withPhaseReturnsNewInstance() {
        ReasoningSpan original = ReasoningSpan.open(TRACE, KEY, EVENT, SpanKind.TEXT, null);

        ReasoningSpan withPhase = original.withPhase("THINKING");

        assertThat(withPhase.phase()).isEqualTo("THINKING");
        assertThat(withPhase.stableKey()).isEqualTo(KEY);
        assertThat(original.phase()).isNull();
    }

    @Test
    @DisplayName("附加来源路径：层级由分隔符数量推导")
    void withAgentPathDerivesDepth() {
        ReasoningSpan top = ReasoningSpan.open(TRACE, KEY, EVENT, SpanKind.TEXT, null)
                .withAgentPath(null);
        assertThat(top.depth()).isZero();
        assertThat(top.agentPath()).isNull();

        ReasoningSpan child = ReasoningSpan.open(TRACE, KEY, EVENT, SpanKind.TEXT, null)
                .withAgentPath("main/researcher");
        assertThat(child.depth()).isEqualTo(2);
        assertThat(child.agentPath()).isEqualTo("main/researcher");

        ReasoningSpan grandChild = ReasoningSpan.open(TRACE, KEY, EVENT, SpanKind.TEXT, null)
                .withAgentPath("main/researcher/sub");
        assertThat(grandChild.depth()).isEqualTo(3);
    }

    @Test
    @DisplayName("附加负载：返回新快照")
    void withPayloadReturnsNewInstance() {
        ReasoningSpan original = ReasoningSpan.closed(TRACE, KEY, EVENT, SpanKind.TEXT,
                ToolResultState.SUCCESS, 10L, null);

        ReasoningSpan withPayload = original.withPayload(Map.of("k", "v"));

        assertThat(withPayload.payload()).containsEntry("k", "v");
        assertThat(original.payload()).isNull();
    }

    @Test
    @DisplayName("终态复用框架的工具结果状态取值")
    void statusReusesFrameworkStates() {
        for (ToolResultState state : ToolResultState.values()) {
            ReasoningSpan span = ReasoningSpan.closed(TRACE, KEY, EVENT, SpanKind.TOOL_CALL,
                    state, 1L, null);
            assertThat(span.status()).isEqualTo(state);
        }
    }

    @Test
    @DisplayName("节点种类完整覆盖轨迹语义")
    void spanKindCoverage() {
        assertThat(SpanKind.values()).containsExactly(
                SpanKind.TURN, SpanKind.MODEL_CALL, SpanKind.TEXT, SpanKind.REASONING,
                SpanKind.DATA, SpanKind.TOOL_CALL, SpanKind.HITL, SpanKind.SUBAGENT,
                SpanKind.INTENT, SpanKind.PLAN, SpanKind.TASK, SpanKind.CONTROL,
                SpanKind.CUSTOM);
    }
}
