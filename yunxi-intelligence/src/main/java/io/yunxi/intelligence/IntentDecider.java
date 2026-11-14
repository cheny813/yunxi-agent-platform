package io.yunxi.intelligence;

import java.util.List;

/**
 * 智能平面。决策融合：对分类器提案做候选闭集校验（越权丢弃）、阈值判定（低置信回落但保留标签）、
 * 无匹配回落，失败时依关键词结果兜底。纯函数，无副作用，便于独立验证。
 */
public class IntentDecider {

    /** 判定结果分类（观测计数按此映射）。 */
    public enum Outcome {
        /** 模型提案被接受。 */
        LLM_ACCEPTED,
        /** 模型给出的 agentId 不在候选集内（越权 / 幻觉），丢弃。 */
        REJECTED_NOT_CANDIDATE,
        /** 置信度低于阈值，回落默认但保留标签。 */
        LOW_CONFIDENCE,
        /** 模型判定无匹配候选。 */
        NO_MATCH,
        /** 采用关键词结果。 */
        KEYWORD,
        /** 全部失败，默认兜底。 */
        FALLBACK
    }

    /** 模型提案进入融合前的占位结果类型（引擎内部使用）。 */
    public static final Outcome LLM_PROPOSED = Outcome.LLM_ACCEPTED;

    /**
     * 融合决策。
     *
     * @param proposal       模型提案（null 表示判定失败）
     * @param keyword        关键词命中结果（null 表示未命中）
     * @param defaultAgentId 默认兜底 Agent
     * @param minConfidence  置信度阈值
     * @param candidates     候选闭集
     * @return 最终决策（永不抛异常）
     */
    public IntentDecision decide(IntentDecision proposal,
            IntentDecision keyword,
            String defaultAgentId,
            double minConfidence,
            List<AgentCandidate> candidates) {
        IntentResolution fallback = IntentResolution.fallback(defaultAgentId);
        if (proposal != null && proposal.resolution() != null) {
            IntentResolution r = proposal.resolution();
            if (r.hasTarget() && !IntentResolution.UNKNOWN_AGENT_ID.equals(r.targetAgentId())) {
                boolean inCandidates = candidates.stream().anyMatch(c -> c.agentId().equals(r.targetAgentId()));
                if (!inCandidates) {
                    return new IntentDecision(fallback, Outcome.REJECTED_NOT_CANDIDATE);
                }
                if (r.confidence() < minConfidence) {
                    return new IntentDecision(new IntentResolution(
                            labelOf(r), r.confidence(), defaultAgentId, r.entities(), r.rewrittenQuery(),
                            IntentResolution.Source.FALLBACK), Outcome.LOW_CONFIDENCE);
                }
                return new IntentDecision(new IntentResolution(
                        labelOf(r), r.confidence(), r.targetAgentId(), r.entities(), r.rewrittenQuery(),
                        IntentResolution.Source.LLM), Outcome.LLM_ACCEPTED);
            }
            return new IntentDecision(fallback, Outcome.NO_MATCH);
        }
        if (keyword != null && keyword.resolution() != null) {
            return new IntentDecision(keyword.resolution(), Outcome.KEYWORD);
        }
        return new IntentDecision(fallback, Outcome.FALLBACK);
    }

    private String labelOf(IntentResolution r) {
        return r.label() == null || r.label().isBlank() ? IntentResolution.UNKNOWN_LABEL : r.label();
    }
}
