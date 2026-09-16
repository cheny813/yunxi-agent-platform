package io.yunxi.platform.trace.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.yunxi.platform.shared.util.SseMessageBuilder;
import io.yunxi.platform.trace.ReasoningSpan;
import io.yunxi.platform.trace.SpanKind;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * SSE 协议投影：把轨迹快照投影为前端既有的 SSE 消息格式。
 *
 * <p>本类<b>不定义事件语义</b>，只做表示转换 —— 语义由轨迹快照承载，本类负责把它写成
 * 前端认识的 wire 格式。这是「One Trace, Many Projections」的落点：新增协议是新增一个
 * 投影，不是新增一套语义。</p>
 *
 * <p><b>兼容性承诺</b>：输出的消息类型与字段格式与既有前端完全一致（前端零改动）。
 * 快照种类到消息类型的对应关系集中在本类，不散落到执行引擎。</p>
 *
 * <p><b>与旧适配路径的关系</b>：旧路径直接消费框架原生事件（事件 → 消息），本投影消费
 * 语义快照（语义 → 消息）。两者产出的 wire 格式一致，差别在于后者可以脱离框架事件重放 ——
 * 历史回放、异构协议接入、离线评估因此共用同一条语义源。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class SseProjection implements TraceProjection<String> {

    /** 前端协议名 */
    public static final String NAME = "sse";

    /** 工具调用结果沿用框架状态值，成功态对应的展示标签 */
    private static final String STATE_SUCCESS = "SUCCESS";

    private final SseMessageBuilder builder;

    public SseProjection(SseMessageBuilder builder) {
        this.builder = builder;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Flux<String> project(Flux<ReasoningSpan> spans, ProjectionContext ctx) {
        // 无状态流变换：逐快照转换，投影之间互不影响
        return spans.concatMap(span -> Flux.fromIterable(convert(span, ctx)));
    }

    /**
     * 单个快照 → 0..n 条 SSE 消息。
     *
     * <p>与旧适配路径的差别：旧路径在处理单个事件时同时做「消耗累积 + 产出消息」两件事，
     * 且依赖请求期间的累积器状态；本投影只读快照自身携带的信息，因此同一快照在任何时候
     * 投影都得到同一结果（可重放）。那些累积与去重逻辑属于归集层职责，已随归集移出适配器。</p>
     *
     * @param span 轨迹快照
     * @param ctx  投影上下文
     * @return SSE 消息列表
     */
    public List<String> convert(ReasoningSpan span, ProjectionContext ctx) {
        List<String> messages = new ArrayList<>();
        SpanKind kind = span.kind();
        if (kind == null) {
            return messages;
        }

        switch (kind) {
            case TEXT -> {
                // 文本增量：开启 / 增量 / 关闭三段都映射为 content（前端按到达顺序累加）
                if (span.delta() != null && !span.delta().isEmpty()) {
                    messages.add(builder.buildContentMessage(span.delta()));
                }
            }
            case REASONING -> {
                if (span.delta() != null && !span.delta().isEmpty()) {
                    messages.add(builder.buildThinkingMessage(span.delta()));
                }
            }
            case TOOL_CALL -> appendToolCallMessages(span, ctx, messages);
            case PLAN, TASK -> appendPlanMessages(span, ctx, messages);
            case SUBAGENT, INTENT, HITL, CONTROL, DATA, CUSTOM, MODEL_CALL, TURN -> {
                // 其余种类在当前 SSE 协议里没有独立消息类型：
                // 它们的可见性由各自的上层投影（AG-UI / OTel）承担，
                // 此处不发明新的消息类型以免破坏前端兼容。
            }
            default -> {
                // 新增种类未在此登记时保持静默：宁可不显示，不可发未定义消息类型
            }
        }
        return messages;
    }

    /**
     * 工具调用：按快照阶段映射为调用 / 调用完成 / 结果 / 结果流式进度等消息。
     *
     * <p>工具结果是与调用并入同一节点的（共享 toolCallId），节点内的快照靠负载中的
     * {@code resultPhase} 区分阶段 —— 见归集器在负载里记录产生该快照的事件类型。
     * 缺了它，前端就无法判断该打开输出区域还是追加文本。</p>
     */
    private void appendToolCallMessages(ReasoningSpan span, ProjectionContext ctx, List<String> messages) {
        Map<String, Object> payload = span.payload();
        String toolCallId = payload == null ? null : stringOf(payload.get("toolCallId"));
        String toolName = payload == null ? null : stringOf(payload.get("toolName"));
        if (toolCallId == null) {
            return;
        }

        switch (span.snapshot()) {
            case OPEN -> {
                if (toolName != null) {
                    messages.add(builder.buildToolCallMessage(toolCallId, toolName));
                }
            }
            case DELTA -> appendToolResultDelta(span, toolCallId, toolName, payload, messages);
            case CLOSED -> {
                if (toolName == null) {
                    return;
                }
                messages.add(builder.buildToolCallDoneMessage(toolCallId, toolName));
                String state = span.status() == null ? "unknown" : span.status().getValue();
                messages.add(builder.buildToolResultMessage(toolCallId, toolName, state));
                if (!STATE_SUCCESS.equalsIgnoreCase(state)) {
                    messages.add(builder.buildAgentStatusMessage(toolName + " (" + state + ")"));
                }
            }
            default -> {
                // 工具调用无其它快照形态
            }
        }
    }

    /**
     * 工具结果的流式进度：按 {@code resultPhase} 分派为「开始」或「增量」消息。
     *
     * <p>结果开始事件本身不带内容（它的语义是「打开输出区域」），增量才带文本，
     * 故两者的产出条件不同：开始只看阶段标记，增量必须同时有非空内容。</p>
     */
    private void appendToolResultDelta(ReasoningSpan span, String toolCallId, String toolName,
                                       Map<String, Object> payload, List<String> messages) {
        if (toolName == null) {
            return;
        }
        String phase = payload == null ? null : stringOf(payload.get("resultPhase"));
        if ("start".equals(phase)) {
            messages.add(builder.buildToolResultStartMessage(toolCallId, toolName));
            return;
        }
        // 无阶段标记的增量也按增量处理：宁可多发一条进度，不可丢内容
        if (span.delta() != null && !span.delta().isEmpty()) {
            messages.add(builder.buildToolResultDeltaMessage(toolCallId, toolName, span.delta()));
        }
    }

    /**
     * 计划与任务：映射为清单更新消息。
     *
     * <p>清单是全量替换语义，故只投影关闭快照携带的完整清单，不投影增量。</p>
     */
    private void appendPlanMessages(ReasoningSpan span, ProjectionContext ctx, List<String> messages) {
        if (!span.isClosed() || span.payload() == null) {
            return;
        }
        Object tasks = span.payload().get("tasks");
        if (tasks instanceof List<?> list && !list.isEmpty()) {
            messages.add(builder.buildTodoUpdateMessage(list, ctx.sessionId()));
        }
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
