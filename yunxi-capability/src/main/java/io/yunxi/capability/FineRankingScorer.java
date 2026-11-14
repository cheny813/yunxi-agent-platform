package io.yunxi.capability;

import java.util.List;

/**
 * 自动选型的精排层扩展点：在规则式粗筛（能力过滤 + 成本升序）得到候选序列后，
 * 对候选做二次排序或重排（例如用本地决策模型对任务打能力画像后重排）。
 *
 * <p>实现契约：入参 {@code ruleBasedCandidates} 是规则式给出的候选逻辑名（catalog 键）有序列表；
 * 返回的列表必须是其子集的重排（不得引入候选池之外的模型），且永不抛异常。
 * 返回 null 或空列表表示不干预，沿用规则式结果。
 */
public interface FineRankingScorer {

    /**
     * 对规则式候选做精排。
     *
     * @param agentId             智能体标识
     * @param signals             任务信号
     * @param ruleBasedCandidates 规则式候选（逻辑名有序）
     * @return 重排后的候选（逻辑名），或空 / null 表示不干预
     */
    List<String> rank(String agentId, TaskSignals signals, List<String> ruleBasedCandidates);
}
