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
     * <p>供剪枝决策使用，包含技能名、使用次数和可选正文（为 null 时跳过相似度合并）。</p>
     */
    class SkillMeta {
        /** 技能名（唯一标识） */
        private String name;
        /** 累计调用次数（从技能仓库元数据获取） */
        private int usageCount;
        /** 技能正文 markdown（为 null 时不参与 TF-IDF 相似度计算） */
        private String content;

        public SkillMeta() {}

        public SkillMeta(String name, int usageCount, String content) {
            this.name = name;
            this.usageCount = usageCount;
            this.content = content;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getUsageCount() { return usageCount; }
        public void setUsageCount(int usageCount) { this.usageCount = usageCount; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    /**
     * 剪枝处置动作。
     *
     * <p>KEEP: 正常保留；ARCHIVE: 因使用率过低归档；MERGE: 与另一技能内容高度重复，合并后只保留一个。</p>
     */
    enum Action {
        /** 保留，不做处置 */
        KEEP,
        /** 低使用率，归档到历史目录 */
        ARCHIVE,
        /** 与另一技能 TF-IDF 余弦相似度超过阈值，合并 */
        MERGE
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
