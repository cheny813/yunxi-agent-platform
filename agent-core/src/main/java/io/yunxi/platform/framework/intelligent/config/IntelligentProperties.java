package io.yunxi.platform.framework.intelligent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能化服务配置属性
 *
 * <p>
 * 保留 IntelligentLlmService、SkillAutoCreator、SessionSearchService 所需的配置。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@ConfigurationProperties(prefix = "intelligent")
public class IntelligentProperties {

    /** 学习闭环配置 */
    private LearningLoopConfig learningLoop = new LearningLoopConfig();

    /** Skill 自动创建配置 */
    private SkillConfig skill = new SkillConfig();

    public LearningLoopConfig getLearningLoop() {
        return learningLoop;
    }

    public void setLearningLoop(LearningLoopConfig learningLoop) {
        this.learningLoop = learningLoop;
    }

    public SkillConfig getSkill() {
        return skill;
    }

    public void setSkill(SkillConfig skill) {
        this.skill = skill;
    }

    public static class LearningLoopConfig {
        /** 是否启用学习闭环 */
        private boolean enabled = true;

        /** 审查使用的模型名称 */
        private String reviewModel = "default";

        /** 会话最大字符数（摘要生成时） */
        private int maxSessionChars = 10000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getReviewModel() {
            return reviewModel;
        }

        public void setReviewModel(String reviewModel) {
            this.reviewModel = reviewModel;
        }

        public int getMaxSessionChars() {
            return maxSessionChars;
        }

        public void setMaxSessionChars(int maxSessionChars) {
            this.maxSessionChars = maxSessionChars;
        }
    }

    /**
     * Skill 自动创建配置
     */
    @Data
    public static class SkillConfig {
        /** 是否启用 Skill 自动创建 */
        private boolean enabled = true;

        /** 自动创建的置信度阈值 */
        private double autoCreatorMinConfidence = 0.5;
    }
}
