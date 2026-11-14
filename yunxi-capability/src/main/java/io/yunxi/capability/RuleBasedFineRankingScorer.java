package io.yunxi.capability;

import java.util.List;

/**
 * 默认精排器：不干预规则式结果（恒等）。
 * 规则式已按「能力满足 + 成本最低」给出候选序列，作为基线精排实现；
 * 当未启用本地决策模型精排时由 {@code RuntimeConfiguration} 装配本实现。
 */
public class RuleBasedFineRankingScorer implements FineRankingScorer {

    @Override
    public List<String> rank(String agentId, TaskSignals signals, List<String> ruleBasedCandidates) {
        return ruleBasedCandidates;
    }
}
