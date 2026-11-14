package io.yunxi.intelligence;

import java.util.Map;

/**
 * 智能平面。意图解析结果：一个分类标签 + 置信度 + 目标 Agent，以及实体与改写文本。
 * {@code Intent} 是它的轻量投影（供执行链路的 {@code AgentCommand.intent} 消费）。
 *
 * @param label          业务意图标签（如 diet / sql / chat）；无匹配时为 {@link #UNKNOWN_LABEL}
 * @param confidence     置信度 0~1
 * @param targetAgentId  目标 Agent；null 或空白表示无匹配，由调用方回落默认 Agent
 * @param entities       实体键值（规范化后的槽位值；无实体时为空 Map）
 * @param rewrittenQuery 实体改写后的查询文本；未启用改写或无改写时为 null
 * @param source         结果来源：LLM（模型判定）/ KEYWORD（规则）/ FALLBACK（默认兜底）
 */
public record IntentResolution(
        String label,
        double confidence,
        String targetAgentId,
        Map<String, String> entities,
        String rewrittenQuery,
        Source source) {

    /** 结果来源枚举。 */
    public enum Source { LLM, KEYWORD, FALLBACK }

    /** 无匹配时的占位标签。 */
    public static final String UNKNOWN_LABEL = "unknown";

    /** 模型输出「无合适候选」时的占位 agentId。 */
    public static final String UNKNOWN_AGENT_ID = "unknown";

    public IntentResolution {
        entities = entities == null ? Map.of() : Map.copyOf(entities);
    }

    /** 构造默认兜底结果（目标 = 平台默认 Agent，无标签无实体）。 */
    public static IntentResolution fallback(String defaultAgentId) {
        return new IntentResolution(UNKNOWN_LABEL, 0.0d, defaultAgentId, Map.of(), null, Source.FALLBACK);
    }

    /** 是否携带可路由的目标 Agent。 */
    public boolean hasTarget() {
        return targetAgentId != null && !targetAgentId.isBlank();
    }

    /** 投影为轻量 {@link Intent}，供执行链路消费。 */
    public Intent asIntent() {
        return new Intent(label, confidence, targetAgentId);
    }
}
