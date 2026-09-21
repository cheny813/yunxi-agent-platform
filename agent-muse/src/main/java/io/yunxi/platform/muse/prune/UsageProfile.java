package io.yunxi.platform.muse.prune;

import java.util.Map;

/**
 * 技能的使用者画像聚合特征。
 *
 * <p>由轨迹（{@code TraceStore}）与用户画像（{@code RedisUserProfileStore}）<b>只读</b>聚合得出，
 * 用于把剪枝判据中的「使用次数」升级为「使用者广度」——使用次数高可能是少数用户反复使用，
 * 使用次数低可能是刚上线，单看次数无法区分。</p>
 *
 * <p><b>只读约束</b>：本对象只承载「画像 → MUSE 决策」方向的输入。MUSE 绝不回写用户画像，
 * 画像抽取链路也绝不读取剪枝结果。这是单向消费关系，不是双向挂钩。</p>
 *
 * <p>为 {@code null} 时表示无画像数据可用（新用户 / Redis 故障 / 轨迹缺失），
 * 剪枝退化为原有两条规则，行为与未引入本特性时一致。</p>
 *
 * @author yunxi-agent-platform
 */
public class UsageProfile {

    /** 去重使用者数：区分「10 人各 1 次」与「1 人 10 次」 */
    private int distinctUsers;

    /** 画像类别分布：类别标识 → 去重使用者数，如 {"职业": 7, "年龄": 3} */
    private Map<String, Integer> categoryDistribution;

    /** 成功调用次数（轨迹终态为成功的 TOOL_CALL 节点数），与 usageCount 的语义区分 */
    private int successCalls;

    public UsageProfile() {
    }

    public UsageProfile(int distinctUsers, Map<String, Integer> categoryDistribution, int successCalls) {
        this.distinctUsers = distinctUsers;
        this.categoryDistribution = categoryDistribution;
        this.successCalls = successCalls;
    }

    public int getDistinctUsers() {
        return distinctUsers;
    }

    public void setDistinctUsers(int distinctUsers) {
        this.distinctUsers = distinctUsers;
    }

    public Map<String, Integer> getCategoryDistribution() {
        return categoryDistribution;
    }

    public void setCategoryDistribution(Map<String, Integer> categoryDistribution) {
        this.categoryDistribution = categoryDistribution;
    }

    public int getSuccessCalls() {
        return successCalls;
    }

    public void setSuccessCalls(int successCalls) {
        this.successCalls = successCalls;
    }
}
