package io.yunxi.intelligence.muse.prune;

import lombok.Data;

/**
 * 技能使用画像：跨用户的调用分布概况，供剪枝器做「翻案」判定（不新增删除）。
 *
 * <p>只读聚合产物；其数据由调用方（观测层）聚合后传入，本类不持有任何数据源。
 */
@Data
public class UsageProfile {

    /** 去重后的调用用户数。 */
    private int distinctUsers;

    /** 调用分类分布（分类 -> 次数）。 */
    private java.util.Map<String, Integer> categoryDistribution = new java.util.HashMap<>();

    /** 成功调用次数。 */
    private long successCalls;
}
