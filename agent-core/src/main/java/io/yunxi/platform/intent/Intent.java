package io.yunxi.platform.intent;

/**
 * 分类产出的意图。
 *
 * <p>框架通用值对象：意图 ID 与展示名由业务方在意图树（intent-tree.yml）中定义，
 * 本 record 仅承载分类结果，不含任何业务语义。未命中任何意图时返回
 * {@link #unknown()}。</p>
 *
 * @param intentId   意图 ID（如 "product.recommend"；未命中为 "unknown"）
 * @param label      展示名（如 "商品推荐"；未命中为 "未知"）
 * @param confidence 置信度 0~1（mode=rule 规则命中基础 0.9，实体加分 +0.05/个，封顶 1.0；
 *                   mode=hybrid 下规则通道输出归一化置信，见 RuleIntentClassifier）
 * @param matchedBy  匹配方式：RULE | LLM | LLM-CACHE | RULE-FALLBACK | NONE
 *                   （历史值 RULE_TREE 已废弃，统一为 RULE）
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record Intent(
        String intentId,
        String label,
        double confidence,
        String matchedBy) {

    /** 未分类意图常量 */
    public static final String UNKNOWN_ID = "unknown";

    /** matchedBy 取值：规则通道命中 */
    public static final String MATCHED_RULE = "RULE";
    /** matchedBy：LLM 通道命中（实时调用） */
    public static final String MATCHED_LLM = "LLM";
    /** matchedBy：LLM 结果缓存命中 */
    public static final String MATCHED_LLM_CACHE = "LLM-CACHE";
    /** matchedBy：LLM 失败，规则低置信保底 */
    public static final String MATCHED_RULE_FALLBACK = "RULE-FALLBACK";
    /** matchedBy：未命中任何意图 */
    public static final String MATCHED_NONE = "NONE";

    public static Intent unknown() {
        return new Intent(UNKNOWN_ID, "未知", 0.0, MATCHED_NONE);
    }

    /** 是否未命中任何意图（供混合编排与观测使用） */
    public boolean isUnknown() {
        return UNKNOWN_ID.equals(intentId);
    }

    /** 以新 matchedBy 复制本意图（供混合编排与观测使用） */
    public Intent withMatchedBy(String newMatchedBy) {
        return new Intent(intentId, label, confidence, newMatchedBy);
    }
}
