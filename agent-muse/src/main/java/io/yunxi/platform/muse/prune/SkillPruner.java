package io.yunxi.platform.muse.prune;

import java.util.List;

/**
 * 技能剪枝/合并决策器。MUSE 自进化闭环最后一环：按使用率与相似度
 * 决定哪些技能应被归档、哪些重复技能应合并。AgentScope 仅有「技能管理」，无此闭环。
 */
public interface SkillPruner {

    /** 对技能库做体检，返回各项处置决策。 */
    List<PruneDecision> review(List<SkillMeta> skills);

    /**
     * 单个技能的轻量元信息。
     *
     * <p>供剪枝决策使用，包含技能名、使用次数、可选正文（为 null 时跳过相似度合并）
     * 以及可选的使用者画像聚合特征（为 null 时跳过画像驱动判据）。</p>
     *
     * <p>JSON 反序列化兼容两种字段名：{@code usageCount}（驼峰）与 {@code usage_count}
     * （下划线，工具文档与外部调用方使用的形状）。未知字段忽略，便于调用方传入扩展信息。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    class SkillMeta {
        /** 技能名（唯一标识） */
        private String name;
        /** 累计调用次数（从技能仓库元数据获取） */
        @com.fasterxml.jackson.annotation.JsonAlias("usage_count")
        private int usageCount;
        /** 技能正文 markdown（为 null 时不参与 TF-IDF 相似度计算） */
        private String content;
        /**
         * 使用者画像聚合特征。为 null 表示无画像数据，画像驱动的判据跳过。
         * 该字段是「画像 → MUSE」的只读输入，MUSE 不据此回写画像。
         */
        private UsageProfile usageProfile;

        public SkillMeta() {}

        public SkillMeta(String name, int usageCount, String content) {
            this.name = name;
            this.usageCount = usageCount;
            this.content = content;
        }

        public SkillMeta(String name, int usageCount, String content, UsageProfile usageProfile) {
            this.name = name;
            this.usageCount = usageCount;
            this.content = content;
            this.usageProfile = usageProfile;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getUsageCount() { return usageCount; }
        public void setUsageCount(int usageCount) { this.usageCount = usageCount; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public UsageProfile getUsageProfile() { return usageProfile; }
        public void setUsageProfile(UsageProfile usageProfile) { this.usageProfile = usageProfile; }
    }

    /**
     * 剪枝处置动作。
     *
     * <p>KEEP: 正常保留；ARCHIVE: 因使用率过低归档；MERGE: 与另一技能内容高度重复，合并后只保留一个；
     * OVERFIT: 使用面过窄（仅单一用户高频使用），标记为「需泛化」。</p>
     */
    enum Action {
        /** 保留，不做处置 */
        KEEP,
        /** 低使用率，归档到历史目录 */
        ARCHIVE,
        /** 与另一技能 TF-IDF 余弦相似度超过阈值，合并 */
        MERGE,
        /**
         * 仅单一用户高频使用，疑为过拟合，标记为「需泛化」。
         *
         * <p>注意与 {@link #ARCHIVE} 的区别：「只有一类用户在用」不等于「没用」，
         * 可能恰恰是该行业的核心技能，直接归档会误杀。故本动作只做标记，不做删除。</p>
         */
        OVERFIT
    }

    /**
     * 剪枝/合并决策结果，包含目标技能名、处置动作、原因和（当动作为 MERGE 时的）合并目标。
     */
    class PruneDecision {
        private String name;
        private Action action;
        private String reason;
        private String mergeWith; // 当 action=MERGE 时指向被合并的目标技能

        public PruneDecision() {}

        public PruneDecision(String name, Action action, String reason) {
            this.name = name;
            this.action = action;
            this.reason = reason;
        }

        public PruneDecision(String name, Action action, String reason, String mergeWith) {
            this.name = name;
            this.action = action;
            this.reason = reason;
            this.mergeWith = mergeWith;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Action getAction() { return action; }
        public void setAction(Action action) { this.action = action; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getMergeWith() { return mergeWith; }
        public void setMergeWith(String mergeWith) { this.mergeWith = mergeWith; }
    }
}
