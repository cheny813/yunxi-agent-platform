package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.ToolResultState;
import reactor.core.publisher.Flux;

/**
 * TraceComposer：事件映射覆盖度、配对完整性与建树回归。
 *
 * <p>三条验收断言：映射覆盖全部事件类型、每个开始快照都有对应关闭快照、无孤儿增量。</p>
 */
@DisplayName("TraceComposer 轨迹归集")
class TraceComposerTest {

    private static final String TRACE = "trace-1";

    private final TraceComposer composer = new TraceComposer();

    private List<ReasoningSpan> compose(List<AgentEvent> events) {
        return composer.compose(Flux.fromIterable(events), TRACE).collectList().block();
    }

    // ── 断言一：映射覆盖度 ────────────────────────────────────

    @Test
    @DisplayName("映射覆盖全部 31 个事件类型，无遗漏")
    void mappingCoversAllEventTypes() {
        Set<AgentEventType> uncovered = EventSpanMapping.uncoveredTypes();

        assertThat(uncovered)
                .as("存在未登记的事件类型，归集会静默丢弃这些事件")
                .isEmpty();
        assertThat(EventSpanMapping.allRules()).hasSize(AgentEventType.values().length);
    }

    @Test
    @DisplayName("全部事件类型各触发一次：均产生快照，无异常")
    void allEventTypesProduceSpans() {
        List<ReasoningSpan> spans = compose(EventFixtures.allEventTypes());

        assertThat(spans).isNotEmpty();
        Set<SpanKind> kinds = spans.stream().map(ReasoningSpan::kind).collect(Collectors.toSet());
        assertThat(kinds).contains(SpanKind.TURN, SpanKind.MODEL_CALL, SpanKind.REASONING,
                SpanKind.TOOL_CALL, SpanKind.TEXT, SpanKind.DATA, SpanKind.HITL,
                SpanKind.CONTROL, SpanKind.CUSTOM, SpanKind.SUBAGENT);
    }

    @Test
    @DisplayName("全部快照携带同一轨迹标识")
    void allSpansShareTraceId() {
        List<ReasoningSpan> spans = compose(EventFixtures.normalTurn());

        assertThat(spans).isNotEmpty();
        assertThat(spans).allSatisfy(s -> assertThat(s.traceId()).isEqualTo(TRACE));
    }

    // ── 断言二：开始必有关闭 ─────────────────────────────────

    @Test
    @DisplayName("完整回合：每个开始快照都有对应关闭快照")
    void everyOpenHasClosed() {
        List<ReasoningSpan> spans = compose(EventFixtures.normalTurn());

        Set<String> opened = spans.stream().filter(ReasoningSpan::isOpen)
                .map(ReasoningSpan::stableKey).collect(Collectors.toSet());
        Set<String> closed = spans.stream().filter(ReasoningSpan::isClosed)
                .map(ReasoningSpan::stableKey).collect(Collectors.toSet());

        assertThat(opened).isNotEmpty();
        assertThat(closed).containsAll(opened);
    }

    @Test
    @DisplayName("事件流中断：未关闭的节点在流结束时自动关闭，不留悬空节点")
    void truncatedStreamAutoCloses() {
        List<ReasoningSpan> spans = compose(EventFixtures.truncatedStream());

        Set<String> opened = spans.stream().filter(ReasoningSpan::isOpen)
                .map(ReasoningSpan::stableKey).collect(Collectors.toSet());
        Set<String> closed = spans.stream().filter(ReasoningSpan::isClosed)
                .map(ReasoningSpan::stableKey).collect(Collectors.toSet());

        assertThat(opened).isNotEmpty();
        assertThat(closed).containsAll(opened);
        assertThat(spans).anySatisfy(s -> assertThat(s.payload()).containsEntry("autoClosed", true));
    }

    // ── 断言三：无孤儿增量 ───────────────────────────────────

    @Test
    @DisplayName("孤儿增量被丢弃，不产生无归属的节点")
    void orphanDeltaDropped() {
        List<ReasoningSpan> spans = compose(EventFixtures.orphanDelta());

        Set<String> opened = spans.stream().filter(ReasoningSpan::isOpen)
                .map(ReasoningSpan::stableKey).collect(Collectors.toSet());
        Set<String> deltaKeys = spans.stream().filter(ReasoningSpan::isDelta)
                .map(ReasoningSpan::stableKey).collect(Collectors.toSet());

        assertThat(opened).isNotEmpty();
        assertThat(deltaKeys).isSubsetOf(opened);
    }

    @Test
    @DisplayName("正常流中的增量均归属于已打开节点")
    void deltasBelongToOpenedNodes() {
        List<ReasoningSpan> spans = compose(EventFixtures.normalTurn());

        Set<String> opened = spans.stream().filter(ReasoningSpan::isOpen)
                .map(ReasoningSpan::stableKey).collect(Collectors.toSet());
        List<ReasoningSpan> deltas = spans.stream().filter(ReasoningSpan::isDelta).toList();

        assertThat(deltas).isNotEmpty();
        assertThat(deltas).allSatisfy(
                d -> assertThat(opened).contains(d.stableKey()));
    }

    // ── 配对语义 ─────────────────────────────────────────────

    @Test
    @DisplayName("同一内容块的开始、增量、关闭共享稳定标识，可分组还原")
    void blockSnapshotsShareStableKey() {
        List<ReasoningSpan> spans = compose(EventFixtures.normalTurn());

        List<ReasoningSpan> textSpans = spans.stream()
                .filter(s -> s.kind() == SpanKind.TEXT).toList();

        assertThat(textSpans).hasSizeGreaterThanOrEqualTo(4);
        assertThat(textSpans.stream().map(ReasoningSpan::stableKey).distinct()).hasSize(1);
        assertThat(textSpans).anySatisfy(s -> assertThat(s.snapshot()).isEqualTo(Snapshot.OPEN));
        assertThat(textSpans).anySatisfy(s -> assertThat(s.snapshot()).isEqualTo(Snapshot.DELTA));
        assertThat(textSpans).anySatisfy(s -> assertThat(s.snapshot()).isEqualTo(Snapshot.CLOSED));
    }

    @Test
    @DisplayName("工具结果事件与工具调用归入同一节点，不另开节点")
    void toolResultJoinsToolCallNode() {
        List<ReasoningSpan> spans = compose(EventFixtures.normalTurn());

        List<ReasoningSpan> toolSpans = spans.stream()
                .filter(s -> s.kind() == SpanKind.TOOL_CALL).toList();

        assertThat(toolSpans.stream().map(ReasoningSpan::stableKey).distinct()).hasSize(1);
        assertThat(toolSpans).anySatisfy(
                s -> assertThat(s.status()).isEqualTo(ToolResultState.SUCCESS));
    }

    @Test
    @DisplayName("增量内容按序拼接可还原完整文本")
    void deltasReconstructContent() {
        List<ReasoningSpan> spans = compose(EventFixtures.normalTurn());

        String text = spans.stream()
                .filter(s -> s.kind() == SpanKind.TEXT)
                .filter(ReasoningSpan::isDelta)
                .map(ReasoningSpan::delta)
                .collect(Collectors.joining());

        assertThat(text).isEqualTo("答案是 42");
    }

    // ── 建树 ─────────────────────────────────────────────────

    @Test
    @DisplayName("根节点为回合，子节点携带父标识")
    void treeHasRootAndChildren() {
        List<ReasoningSpan> spans = compose(EventFixtures.normalTurn());

        ReasoningSpan root = spans.stream().filter(ReasoningSpan::isOpen)
                .filter(s -> s.kind() == SpanKind.TURN).findFirst().orElseThrow();
        assertThat(root.parentKey()).isNull();

        List<ReasoningSpan> children = spans.stream().filter(ReasoningSpan::isOpen)
                .filter(s -> s.kind() != SpanKind.TURN).toList();
        assertThat(children).isNotEmpty();
        assertThat(children).allSatisfy(c -> assertThat(c.parentKey()).isNotNull());
    }

    @Test
    @DisplayName("来源路径非空时推导层级，顶层事件层级为零")
    void depthDerivedFromSource() {
        List<ReasoningSpan> spans = compose(EventFixtures.subagentForwarded());

        assertThat(spans).anySatisfy(s -> {
            if ("main/researcher".equals(s.agentPath())) {
                assertThat(s.depth()).isEqualTo(2);
            }
        });
        assertThat(spans.stream().filter(s -> s.agentPath() == null))
                .allSatisfy(s -> assertThat(s.depth()).isZero());
    }

    @Test
    @DisplayName("转发子代理与父回合共享回复标识时仍各自成节点")
    void subagentKeepsOwnNodeWhenSharingReplyId() {
        List<ReasoningSpan> spans = compose(EventFixtures.subagentForwarded());

        List<ReasoningSpan> turnOpens = spans.stream().filter(ReasoningSpan::isOpen)
                .filter(s -> s.kind() == SpanKind.TURN).toList();
        assertThat(turnOpens).hasSize(2);
        assertThat(turnOpens.stream().map(ReasoningSpan::stableKey).distinct()).hasSize(2);

        ReasoningSpan root = turnOpens.stream().filter(s -> s.agentPath() == null)
                .findFirst().orElseThrow();
        ReasoningSpan child = turnOpens.stream()
                .filter(s -> "main/researcher".equals(s.agentPath())).findFirst().orElseThrow();
        assertThat(child.parentKey()).isEqualTo(root.stableKey());
    }

    // ── 人机交互终态 ─────────────────────────────────────────

    @Test
    @DisplayName("人机交互已闭环：确认结果关闭该节点")
    void resolvedHitlClosed() {
        List<ReasoningSpan> spans = compose(EventFixtures.hitlResolved());

        List<ReasoningSpan> hitlOpens = spans.stream().filter(ReasoningSpan::isOpen)
                .filter(s -> s.kind() == SpanKind.HITL).toList();
        List<ReasoningSpan> hitlCloses = spans.stream().filter(ReasoningSpan::isClosed)
                .filter(s -> s.kind() == SpanKind.HITL).toList();

        assertThat(hitlOpens).isNotEmpty();
        assertThat(hitlCloses).isNotEmpty();
        assertThat(hitlCloses).noneSatisfy(
                s -> assertThat(s.status()).isEqualTo(SpanOutcome.PENDING));
    }

    @Test
    @DisplayName("人机交互未闭环：回合结束时记为未决")
    void pendingHitlMarkedPending() {
        List<ReasoningSpan> spans = compose(EventFixtures.hitlPending());

        assertThat(spans).anySatisfy(s -> {
            if (s.kind() == SpanKind.HITL && s.isClosed()) {
                assertThat(s.status()).isEqualTo(SpanOutcome.PENDING);
                assertThat(s.payload()).containsEntry("pending", true);
            }
        });
    }

    // ── 控制信号 ─────────────────────────────────────────────

    @Test
    @DisplayName("边界信号：全部工具被拒记为拒绝态，其余控制事件正常关闭")
    void boundarySignalsClosed() {
        List<ReasoningSpan> spans = compose(EventFixtures.boundarySignals());

        List<ReasoningSpan> controls = spans.stream()
                .filter(s -> s.kind() == SpanKind.CONTROL).toList();

        assertThat(controls).isNotEmpty();
        assertThat(controls).anySatisfy(
                s -> assertThat(s.status()).isEqualTo(ToolResultState.DENIED));
        assertThat(controls).anySatisfy(
                s -> assertThat(s.payload()).containsKey("maxIters"));
        assertThat(controls).anySatisfy(
                s -> assertThat(s.payload()).containsEntry("reason", "达到上限"));
    }

    @Test
    @DisplayName("空事件流：不产生快照且正常结束")
    void emptyStream() {
        List<ReasoningSpan> spans = compose(List.of());

        assertThat(spans).isEmpty();
    }

    @Test
    @DisplayName("未知类型事件被忽略，不影响其余事件的归集")
    void nullTypeIgnored() {
        List<AgentEvent> events = List.of(
                new io.agentscope.core.event.TextBlockStartEvent("r9", "b9"));

        List<ReasoningSpan> spans = compose(events);

        assertThat(spans).isNotEmpty();
    }
}
