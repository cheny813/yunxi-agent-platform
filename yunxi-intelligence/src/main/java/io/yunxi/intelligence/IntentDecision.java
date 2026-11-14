package io.yunxi.intelligence;

/**
 * 智能平面。融合决策结果：最终采用的解析结果 + 发生哪类判定（观测计数按 outcome 映射）。
 */
public record IntentDecision(IntentResolution resolution, IntentDecider.Outcome outcome) {
}
