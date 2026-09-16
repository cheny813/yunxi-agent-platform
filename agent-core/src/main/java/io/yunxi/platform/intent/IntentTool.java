package io.yunxi.platform.intent;

import java.util.List;

import org.springframework.stereotype.Component;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

/**
 * 意图分类工具（模型侧）。
 *
 * <p>作为统一推理运行时内的工具节点存在：模型在处理用户请求时调用本工具完成意图 / 场景 / 领域识别。
 * 调用产生的 {@code TOOL_CALL} 事件经 {@code TraceComposer} 重分类为 {@code SpanKind.INTENT}，
 * 天然携带 {@code toolCallId}，从而满足轨迹验收项②（INTENT span 与其所属工具的 toolCallId 可配对）。</p>
 *
 * <p>本工具仅负责意图识别与可观测产物，不介入路由 —— 路由仍由 IntentPipelineInterceptor 在预调用阶段完成。</p>
 */
@Slf4j
@Component
public class IntentTool {

    private final IntentEngine intentEngine;

    public IntentTool(IntentEngine intentEngine) {
        this.intentEngine = intentEngine;
    }

    @Tool(name = "intent_classify",
            description = "在分析用户请求并组织回答之前调用本工具，识别请求的意图、场景与领域。"
                    + "返回结构化 JSON（originalQuery / rewrittenQuery / sceneName / domain / intentLabel / degraded）。")
    public String classify(
            @ToolParam(name = "query", description = "待识别的用户原始请求文本") String query) {
        try {
            IntentContext context = new IntentContext(query, null, null, null, List.of(), null, null);
            IntentResult result = intentEngine.analyze(context);
            return toJson(result);
        } catch (Exception e) {
            log.warn("意图工具执行失败: {}", e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String toJson(IntentResult r) {
        String intentLabel = r.intent() == null ? "unknown" : r.intent().label();
        return "{\"originalQuery\":" + q(r.originalQuery())
                + ",\"rewrittenQuery\":" + q(r.rewrittenQuery())
                + ",\"sceneName\":" + q(r.sceneName())
                + ",\"domain\":" + q(r.domain())
                + ",\"intentLabel\":" + q(intentLabel)
                + ",\"degraded\":" + r.degraded() + "}";
    }

    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
