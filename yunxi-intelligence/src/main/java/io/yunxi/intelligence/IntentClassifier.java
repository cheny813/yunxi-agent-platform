package io.yunxi.intelligence;

import java.util.List;
import java.util.Map;

/**
 * 智能平面。意图分类判定器 SPI：给定归一化文本、规则抽取的实体与候选闭集，返回一次结构化判定。
 *
 * <p>默认实现为本地决策模型（经 {@code ModelResolver} 解析 {@code defaults.decision}）；
 * 判定失败（模型不可用 / 超时 / 输出非法）返回 {@code null}，由引擎按「关键词 → 默认」降级，
 * 绝不抛出到入口。</p>
 */
public interface IntentClassifier {

    /**
     * 执行一次分类判定。
     *
     * @param normalizedText 归一化后的用户文本（已截断由实现负责）
     * @param entities       规则槽位抽取的实体（供分类器参考，可修正）
     * @param candidates     候选闭集
     * @return 判定结果（source = LLM），失败时返回 null
     */
    IntentResolution classify(String normalizedText, Map<String, String> entities, List<AgentCandidate> candidates);
}
