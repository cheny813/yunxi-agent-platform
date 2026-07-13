package io.yunxi.platform.intelligent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能化服务配置属性
 *
 * <p>
 * 保留 IntelligentLlmService、SkillAutoCreator、SessionSearchService 所需的配置
 * </p>
 *
 * @author yunxi-agent-platform
 */
@ConfigurationProperties(prefix = "intelligent")
public class IntelligentProperties {

    /** 学习循环配置 */
    private LearningLoopConfig learningLoop = new LearningLoopConfig();

    /** Skill 自动创建配置 */
    private SkillConfig skill = new SkillConfig();

    /**
     * 获取学习循环配置
     *
     * @return 学习循环配置
     */
    public LearningLoopConfig getLearningLoop() {
        return learningLoop;
    }

    /**
     * 设置学习循环配置
     *
     * @param learningLoop 学习循环配置
     */
    public void setLearningLoop(LearningLoopConfig learningLoop) {
        this.learningLoop = learningLoop;
    }

    /**
     * 获取 Skill 自动创建配置
     *
     * @return Skill 自动创建配置
     */
    public SkillConfig getSkill() {
        return skill;
    }

    /**
     * 设置 Skill 自动创建配置
     *
     * @param skill Skill 自动创建配置
     */
    public void setSkill(SkillConfig skill) {
        this.skill = skill;
    }

    /**
     * 学习循环配置
     * <p>
     * 定义会话摘要/学习循环的行为参数。
     * </p>
     */
    public static class LearningLoopConfig {
        /** 是否启用学习循环 */
        private boolean enabled = true;

        /** 审核使用的模型名称 */
        private String reviewModel = "default";

        /** 会话最大字符数（摘要生成时） */
        private int maxSessionChars = 10000;

        /**
         * 是否启用学习循环
         *
         * @return 启用返回 true
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用学习循环
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取审核使用的模型名称
         *
         * @return 模型名称
         */
        public String getReviewModel() {
            return reviewModel;
        }

        /**
         * 设置审核使用的模型名称
         *
         * @param reviewModel 模型名称
         */
        public void setReviewModel(String reviewModel) {
            this.reviewModel = reviewModel;
        }

        /**
         * 获取会话最大字符数（摘要生成时使用）
         *
         * @return 最大字符数
         */
        public int getMaxSessionChars() {
            return maxSessionChars;
        }

        /**
         * 设置会话最大字符数（摘要生成时使用）
         *
         * @param maxSessionChars 最大字符数
         */
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
