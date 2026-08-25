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
 * @param confidence 置信度 0~1（规则命中基础 0.9，实体加分 +0.05/个，封顶 1.0）
 * @param matchedBy  匹配方式：RULE_TREE | LLM | VECTOR（M1 仅产生 RULE_TREE）
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

    public static Intent unknown() {
        return new Intent(UNKNOWN_ID, "未知", 0.0, "NONE");
    }
}
