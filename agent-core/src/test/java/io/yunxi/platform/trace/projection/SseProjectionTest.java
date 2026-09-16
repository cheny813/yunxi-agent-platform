package io.yunxi.platform.trace.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.ToolResultState;
import io.yunxi.platform.shared.util.SseMessageBuilder;
import io.yunxi.platform.trace.ReasoningSpan;
import io.yunxi.platform.trace.SpanKind;
import reactor.core.publisher.Flux;

/**
 * {@link SseProjection} 单元测试。
 *
 * <p>M2 的验收要求是「既有 SSE 消息形态的等价性回归、前端零改动」，故本类不测
 * 「投影是否好看」，只测<b>投影输出是否仍是前端认识的既有格式</b>。</p>
 *
 * <p>本类同时锁住一条设计边界：投影是<b>无状态流变换</b>。同一快照序列投影两次必须得到
 * 完全相同的结果，且投影不得依赖到达顺序之外的外部状态 —— 这条是历史回放与离线评估
 * 能与实时输出共用同一语义源的前提。</p>
 */
@DisplayName("SseProjection SSE 协议投影")
class SseProjectionTest {

    private SseProjection projection;
    private ProjectionContext ctx;

    @BeforeEach
    void setUp() {
        projection = new SseProjection(new SseMessageBuilder());
        ctx = ProjectionContext.of("trace-1", "u1", "s1", Map.of());
    }

    private List<String> project(ReasoningSpan... spans) {
        return projection.project(Flux.fromArray(spans), ctx).collectList().block();
    }

    @Test
    @DisplayName("协议名固定为 sse")
    void nameIsSse() {
        assertThat(projection.name()).isEqualTo("sse");
    }

    @Test
    @DisplayName("文本增量 → content 消息")
    void textDeltaBecomesContent() {
        List<String> out = project(
                ReasoningSpan.delta("trace-1", "k1", "e1", SpanKind.TEXT, "你好"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("content").contains("你好");
    }

    @Test
    @DisplayName("思考增量 → thinking 消息")
    void reasoningDeltaBecomesThinking() {
        List<String> out = project(
                ReasoningSpan.delta("trace-1", "k1", "e1", SpanKind.REASONING, "让我想想"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("thinking").contains("让我想想");
    }

    @Test
    @DisplayName("文本/思考的空增量不产出消息（不发明空事件）")
    void emptyDeltaProducesNothing() {
        assertThat(project(ReasoningSpan.delta("trace-1", "k1", "e1", SpanKind.TEXT, "")))
                .isEmpty();
        assertThat(project(ReasoningSpan.delta("trace-1", "k1", "e1", SpanKind.TEXT, null)))
                .isEmpty();
    }

    @Test
    @DisplayName("工具调用开启 → tool_call 消息")
    void toolCallOpenBecomesToolCall() {
        ReasoningSpan open = ReasoningSpan.open("trace-1", "k1", "e1", SpanKind.TOOL_CALL, "turn")
                .withPayload(Map.of("toolCallId", "call-1", "toolName", "read_file"));

        List<String> out = project(open);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("tool_call").contains("call-1").contains("read_file");
    }

    @Test
    @DisplayName("工具调用关闭 → 调用完成 + 结果两条消息，成功态不补状态提示")
    void toolCallClosedBecomesDoneAndResult() {
        ReasoningSpan closed = ReasoningSpan.closed("trace-1", "k1", "e1", SpanKind.TOOL_CALL,
                        ToolResultState.SUCCESS, 12L,
                        Map.of("toolCallId", "call-1", "toolName", "read_file"));

        List<String> out = project(closed);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).contains("tool_call_done");
        assertThat(out.get(1)).contains("tool_result");
    }

    @Test
    @DisplayName("工具调用失败态 → 额外补一条 status 提示（前端需感知失败）")
    void toolCallFailedAddsStatusHint() {
        ReasoningSpan closed = ReasoningSpan.closed("trace-1", "k1", "e1", SpanKind.TOOL_CALL,
                        ToolResultState.ERROR, 5L,
                        Map.of("toolCallId", "call-1", "toolName", "read_file"));

        List<String> out = project(closed);

        assertThat(out).hasSize(3);
        assertThat(out.get(2)).contains("agent_status");
    }

    @Test
    @DisplayName("工具调用缺少标识时不产出消息（宁可静默，不可发残缺负载）")
    void toolCallWithoutIdsProducesNothing() {
        ReasoningSpan open = ReasoningSpan.open("trace-1", "k1", "e1", SpanKind.TOOL_CALL, "turn");

        assertThat(project(open)).isEmpty();
    }

    @Test
    @DisplayName("任务清单关闭快照 → todo_update 消息（携带会话标识）")
    void planClosedBecomesTodoUpdate() {
        ReasoningSpan closed = ReasoningSpan.closed("trace-1", "k1", "e1", SpanKind.PLAN,
                ToolResultState.SUCCESS, 3L,
                Map.of("tasks", List.of(Map.of("id", "t1", "subject", "读取文件"))));

        List<String> out = project(closed);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("todo_update").contains("s1");
    }

    @Test
    @DisplayName("清单开启快照不产出清单消息（全量替换语义只认关闭快照）")
    void planOpenDoesNotEmitTodoUpdate() {
        ReasoningSpan open = ReasoningSpan.open("trace-1", "k1", "e1", SpanKind.PLAN, "turn")
                .withPayload(Map.of("tasks", List.of(Map.of("id", "t1"))));

        assertThat(project(open)).isEmpty();
    }

    @Test
    @DisplayName("工具结果开始：映射为 tool_result_start（前端据此打开输出区域）")
    void toolResultStartBecomesToolResultStart() {
        ReasoningSpan span = ReasoningSpan
                .delta("trace-1", "k1", "e1", SpanKind.TOOL_CALL, "")
                .withPayload(Map.of("toolCallId", "call-1", "toolName", "read_file",
                        "resultPhase", "start"));

        List<String> out = project(span);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("tool_result_start").contains("call-1");
    }

    @Test
    @DisplayName("工具结果增量：映射为 tool_result_delta（前端追加到输出区域）")
    void toolResultDeltaBecomesToolResultDelta() {
        ReasoningSpan span = ReasoningSpan
                .delta("trace-1", "k1", "e1", SpanKind.TOOL_CALL, "读取中…")
                .withPayload(Map.of("toolCallId", "call-1", "toolName", "read_file",
                        "resultPhase", "delta"));

        List<String> out = project(span);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("tool_result_delta").contains("读取中…");
    }

    @Test
    @DisplayName("工具结果开始无内容也产出消息（它的语义是打开区域，不是内容）")
    void toolResultStartNeedsNoContent() {
        ReasoningSpan start = ReasoningSpan
                .delta("trace-1", "k1", "e1", SpanKind.TOOL_CALL, "")
                .withPayload(Map.of("toolCallId", "c", "toolName", "t", "resultPhase", "start"));

        assertThat(project(start))
                .as("开始事件的语义是打开区域，无文本也应产出")
                .hasSize(1);
    }

    @Test
    @DisplayName("工具结果增量无内容则不产出（不发明空进度）")
    void toolResultDeltaWithNoContentProducesNothing() {
        ReasoningSpan delta = ReasoningSpan
                .delta("trace-1", "k1", "e1", SpanKind.TOOL_CALL, "")
                .withPayload(Map.of("toolCallId", "c", "toolName", "t", "resultPhase", "delta"));

        assertThat(project(delta)).isEmpty();
    }

    @Test
    @DisplayName("未在 SSE 协议中定义的种类保持静默，不发明新消息类型")
    void undefinedKindsStaySilent() {
        for (SpanKind kind : List.of(SpanKind.INTENT, SpanKind.SUBAGENT, SpanKind.HITL,
                SpanKind.CONTROL, SpanKind.CUSTOM)) {
            ReasoningSpan span = ReasoningSpan.open("trace-1", "k-" + kind, "e1", kind, "turn")
                    .withPayload(Map.of("x", "y"));
            assertThat(project(span))
                    .as("%s 在当前 SSE 协议里没有独立消息类型，应静默", kind)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("无状态：同一快照序列投影两次，消息类型与内容完全一致（可重放）")
    void projectionIsStatelessAndReplayable() {
        ReasoningSpan[] spans = {
                ReasoningSpan.delta("trace-1", "k1", "e1", SpanKind.TEXT, "abc"),
                ReasoningSpan.delta("trace-1", "k2", "e2", SpanKind.TEXT, "def"),
                ReasoningSpan.closed("trace-1", "k3", "e3", SpanKind.TOOL_CALL,
                        ToolResultState.SUCCESS, 1L,
                        Map.of("toolCallId", "c1", "toolName", "t"))
        };

        // 只比较消息的类型与内容，不比较时间戳：时间戳由消息构建器按产出时刻生成，
        // 是 wire 层的既有语义（前端据它排序与展示），不属于投影的可重放范围。
        // 语义可重放才是承诺 —— 同一轨迹在任意时刻投影出的消息序列应当一致。
        assertThat(stripTimestamps(project(spans)))
                .as("无状态投影的语义必须可重放 —— 这是历史回放与离线评估复用实时语义源的前提")
                .isEqualTo(stripTimestamps(project(spans)));
    }

    /** 去掉 JSON 中的 timestamp 字段，只保留类型与内容用于比较。 */
    private static List<String> stripTimestamps(List<String> messages) {
        return messages.stream()
                .map(m -> m.replaceAll("\"timestamp\":[0-9.E-]+,", ""))
                .toList();
    }

    @Test
    @DisplayName("保留上下文入参不过期：状态槽与反向入参当前为空（留口未实现）")
    void stateAndInboundAreReservedButUnused() {
        assertThat(ctx.state())
                .as("状态槽已提供但当前无使用者")
                .isEmpty();
        assertThat(ctx.inbound())
                .as("反向通道已提供但当前无生产者")
                .isEmpty();

        ctx.state().put("lastSnapshot", "x");
        assertThat(ctx.state()).containsKey("lastSnapshot");
        assertThat(ctx.inbound()).as("反向入参构造后不可变").isEmpty();
    }
}
