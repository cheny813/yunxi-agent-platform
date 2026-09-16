package io.yunxi.platform.trace.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.yunxi.platform.trace.ReasoningSpan;
import io.yunxi.platform.trace.SpanKind;
import io.yunxi.platform.trace.Snapshot;
import io.agentscope.core.message.ToolResultState;
import reactor.core.publisher.Flux;

import org.springframework.stereotype.Component;

/**
 * AG-UI 投影：把推理轨迹快照流映射为 AG-UI 协议事件（JSON 文本）。
 *
 * <p>映射遵循 {@code docs/application-agent-architecture-upgrade.md} §6 的五族同构 + §6.1 四项反压定案：
 * <ul>
 *   <li>A′ model/provider 来自中间件注入通道（{@code SpanAttributeBag}），由 composer 是否入 payload 决定；
 *       当前 composer 尚未把该通道读入 span payload，故投影按 payload 是否携带防御性读取，缺失则省略（不阻塞）。</li>
 *   <li>B HITL 未决态 → {@code RUN_PAUSED}/{@code RUN_RESUMED}（协议级 HITL pattern），未决终态由 span.status=INTERRUPTED 表达。</li>
 *   <li>C cancelled → TURN 关闭时按 status=CANCELLED 映射为 {@code RUN_FINISHED} outcome=cancelled。</li>
 *   <li>D 有状态/反向通道只留口：STATE 仅发 {@code STATE_SNAPSHOT}，不发 {@code STATE_DELTA}（差量补丁需跨事件记忆，当前未实现）。</li>
 * </ul>
 *
 * <p>AG-UI 1.0 规范截至 2026-09-16 经核实仍处于<b>草案</b>状态（docs.ag-ui.com 左侧导航标
 * "1.0 Specification (Draft)"，且 deprecated 事件说明 "will be removed in version 1.0.0" 暗示 1.0.0 未发布）。
 * 草案<b>没有</b>定义正式的 per-model / per-provider token usage 事件：token usage 由通用 {@code metadata} 字段携带
 * （任意事件均可挂，官方建议在最后一条消息事件如 {@code RUN_FINISHED} / {@code TEXT_MESSAGE_END} 上发送，消费者按 key 合并），
 * 且未给出标准字段名。故 per-model usage 当前以 {@code CUSTOM} 事件（name=model_call_usage）作非标准占位，
 * <b>回填接缝</b>：1.0 冻结后将其迁移为 {@code RUN_FINISHED.metadata} 中的 model/provider/usage 结构，
 * 而非保留 CUSTOM 事件（投影为无状态 flatMap，把多次模型调用的 usage 缓冲进 RUN_FINISHED 当前不在范围内，冻结时再评估）。
 * 不现在猜一个错的形状（外部规范草案只作旁证，见设计纪律 root cause 6）。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AguiProjection implements TraceProjection<String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final char KEY_SEPARATOR = '|';

    @Override
    public String name() {
        return "ag-ui";
    }

    @Override
    public Flux<String> project(Flux<ReasoningSpan> spans, ProjectionContext ctx) {
        return spans.flatMap(span -> Flux.fromIterable(convert(span, ctx)));
    }

    /**
     * 把单个快照投影为一个或多个 AG-UI 事件 JSON（不可投影的快照返回空列表）。
     */
    public List<String> convert(ReasoningSpan span, ProjectionContext ctx) {
        List<String> out = new ArrayList<>();
        if (span == null) {
            return out;
        }
        SpanKind kind = span.kind();
        switch (kind) {
            case TURN -> convertTurn(span, out);
            case TEXT -> convertText(span, out);
            case REASONING -> convertReasoning(span, out);
            case TOOL_CALL -> convertToolCall(span, out);
            case PLAN, TASK -> convertState(span, out);
            case SUBAGENT -> convertSubagent(span, out);
            case HITL -> convertHitl(span, out);
            case CONTROL -> convertControl(span, out);
            case MODEL_CALL -> convertModelCall(span, out);
            case CUSTOM -> convertCustom(span, out);
            case DATA -> convertData(span, out);
            case INTENT -> { /* INTENT 由上层消费，不投影为独立 AG-UI 事件 */ }
        }
        return out;
    }

    // ── 生命周期 ──────────────────────────────────────────────

    private void convertTurn(ReasoningSpan span, List<String> out) {
        if (span.isOpen()) {
            out.add(json(ev("RUN_STARTED").put("runId", span.traceId())));
            return;
        }
        if (span.isClosed()) {
            ToolResultState status = span.status();
            if (status != null && status.name().equals("ERROR")) {
                ObjectNode e = ev("RUN_ERROR");
                e.put("runId", span.traceId());
                e.put("message", str(span.payload(), "result", "run finished with error"));
                out.add(json(e));
            } else {
                ObjectNode f = ev("RUN_FINISHED");
                f.put("runId", span.traceId());
                // §6.1 B：未决终态 INTERRUPTED → AG-UI outcome=interrupted；
                // 框架 ToolResultState 无 CANCELLED 常量，取消以 INTERRUPTED 表达（草案未冻结，按现状映射）
                f.put("outcome", status != null && status.name().equals("INTERRUPTED") ? "interrupted" : "success");
                out.add(json(f));
            }
        }
    }

    // ── 文本块 ────────────────────────────────────────────────

    private void convertText(ReasoningSpan span, List<String> out) {
        String messageId = idOf(span);
        if (span.isOpen()) {
            out.add(json(ev("TEXT_MESSAGE_START").put("messageId", messageId).put("role", "assistant")));
        } else if (span.isDelta()) {
            out.add(json(ev("TEXT_MESSAGE_CONTENT").put("messageId", messageId).put("delta", orEmpty(span.delta()))));
        } else if (span.isClosed()) {
            out.add(json(ev("TEXT_MESSAGE_END").put("messageId", messageId)));
        }
    }

    // ── 思考块 ────────────────────────────────────────────────

    private void convertReasoning(ReasoningSpan span, List<String> out) {
        String messageId = idOf(span);
        if (span.isOpen()) {
            out.add(json(ev("REASONING_MESSAGE_START").put("messageId", messageId).put("role", "assistant")));
        } else if (span.isDelta()) {
            out.add(json(ev("REASONING_MESSAGE_CONTENT").put("messageId", messageId).put("delta", orEmpty(span.delta()))));
        } else if (span.isClosed()) {
            out.add(json(ev("REASONING_MESSAGE_END").put("messageId", messageId)));
        }
    }

    // ── 工具调用 ──────────────────────────────────────────────

    private void convertToolCall(ReasoningSpan span, List<String> out) {
        String toolCallId = str(span.payload(), "toolCallId", idOf(span));
        if (span.isOpen()) {
            ObjectNode s = ev("TOOL_CALL_START");
            s.put("toolCallId", toolCallId);
            s.put("toolName", str(span.payload(), "toolName", ""));
            s.putObject("args");
            out.add(json(s));
            return;
        }
        String resultPhase = str(span.payload(), "resultPhase", null);
        if (span.isDelta()) {
            if ("delta".equals(resultPhase)) {
                out.add(json(ev("TOOL_CALL_RESULT").put("toolCallId", toolCallId).put("result", orEmpty(span.delta()))));
            } else if (!"start".equals(resultPhase)) {
                out.add(json(ev("TOOL_CALL_ARGS").put("toolCallId", toolCallId).put("delta", orEmpty(span.delta()))));
            }
            return;
        }
        if (span.isClosed()) {
            out.add(json(ev("TOOL_CALL_END").put("toolCallId", toolCallId)));
            if ("end".equals(resultPhase) && span.payload() != null && span.payload().containsKey("result")) {
                out.add(json(ev("TOOL_CALL_RESULT").put("toolCallId", toolCallId).put("result", orEmpty(str(span.payload(), "result", "")))));
            }
        }
    }

    // ── 状态（计划 / 任务）──────────────────────────────────

    private void convertState(ReasoningSpan span, List<String> out) {
        ObjectNode snap = MAPPER.createObjectNode();
        if (span.payload() != null) {
            for (Map.Entry<String, Object> e : span.payload().entrySet()) {
                snap.set(e.getKey(), MAPPER.valueToTree(e.getValue()));
            }
        }
        out.add(json(ev("STATE_SNAPSHOT").set("snapshot", snap)));
    }

    // ── 子代理 ────────────────────────────────────────────────

    private void convertSubagent(ReasoningSpan span, List<String> out) {
        if (!span.isOpen()) {
            return;
        }
        ObjectNode s = ev("SUBAGENT_STARTED");
        s.put("subagentName", str(span.payload(), "label", str(span.payload(), "subagentId", "subagent")));
        if (span.parentKey() != null) {
            int idx = span.parentKey().lastIndexOf(KEY_SEPARATOR);
            s.put("parentToolCallId", idx >= 0 ? span.parentKey().substring(idx + 1) : span.parentKey());
        }
        out.add(json(s));
    }

    // ── 人机交互（协议级 HITL pattern）────────────────────────

    private void convertHitl(ReasoningSpan span, List<String> out) {
        if (span.isOpen()) {
            ObjectNode p = ev("RUN_PAUSED");
            p.put("runId", span.traceId());
            ArrayNode interrupts = p.putArray("interrupts");
            Object toolCalls = span.payload() == null ? null : span.payload().get("toolCalls");
            if (toolCalls != null) {
                interrupts.add(MAPPER.valueToTree(toolCalls));
            }
            out.add(json(p));
        } else if (span.isClosed()) {
            out.add(json(ev("RUN_RESUMED").put("runId", span.traceId())));
        }
    }

    // ── 控制信号 ──────────────────────────────────────────────

    private void convertControl(ReasoningSpan span, List<String> out) {
        if (!span.isClosed()) {
            return;
        }
        ObjectNode f = ev("STEP_FINISHED");
        f.put("stepName", span.phase() != null ? span.phase() : "control");
        if (span.payload() != null) {
            f.set("details", MAPPER.valueToTree(span.payload()));
        }
        out.add(json(f));
    }

    // ── 模型调用（per-model usage 草案未冻结，CUSTOM 占位）────

    private void convertModelCall(ReasoningSpan span, List<String> out) {
        if (!span.isClosed() || span.payload() == null || !span.payload().containsKey("usage")) {
            return;
        }
        ObjectNode v = MAPPER.createObjectNode();
        v.put("model", str(span.payload(), "model", null));
        v.put("provider", str(span.payload(), "provider", null));
        v.set("usage", MAPPER.valueToTree(span.payload().get("usage")));
        out.add(json(ev("CUSTOM").put("name", "model_call_usage").set("value", v)));
    }

    // ── 自定义事件 ────────────────────────────────────────────

    private void convertCustom(ReasoningSpan span, List<String> out) {
        if (!span.isClosed()) {
            return;
        }
        ObjectNode c = ev("CUSTOM");
        c.put("name", str(span.payload(), "name", "custom"));
        if (span.payload() != null && span.payload().containsKey("value")) {
            c.set("value", MAPPER.valueToTree(span.payload().get("value")));
        }
        out.add(json(c));
    }

    // ── 数据块（AG-UI 无独立 data 族，CUSTOM 兜底）────────────

    private void convertData(ReasoningSpan span, List<String> out) {
        ObjectNode v = MAPPER.createObjectNode();
        v.put("delta", orEmpty(span.delta()));
        if (span.payload() != null) {
            v.set("payload", MAPPER.valueToTree(span.payload()));
        }
        out.add(json(ev("CUSTOM").put("name", "data").set("value", v)));
    }

    // ── 工具方法 ──────────────────────────────────────────────

    private static ObjectNode ev(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }

    private static String json(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"type\":\"CUSTOM\",\"name\":\"serialize_error\",\"value\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String idOf(ReasoningSpan span) {
        String key = span.stableKey();
        if (key == null) {
            return "unknown";
        }
        int idx = key.lastIndexOf(KEY_SEPARATOR);
        return idx >= 0 ? key.substring(idx + 1) : key;
    }

    private static String str(Map<String, Object> payload, String key, String fallback) {
        if (payload == null) {
            return fallback;
        }
        Object v = payload.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
