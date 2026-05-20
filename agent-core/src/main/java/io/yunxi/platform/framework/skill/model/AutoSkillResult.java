package io.yunxi.platform.framework.skill.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自动技能创建结果
 * <p>
 * 描述自动创建技能的结果和状态
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Data
public class AutoSkillResult {

    /**
     * 结果状态
     */
    private Status status;

    /**
     * 技能名称
     */
    private String skillName;

    /**
     * 技能描述
     */
    private String description;

    /**
     * 技能文件路径
     */
    private String skillPath;

    /**
     * 置信度（0-1）
     */
    private double confidence;

    /**
     * 错误消息（如果失败）
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 使用的成功模式
     */
    private SuccessPattern sourcePattern;

    /**
     * 结果状态枚举
     */
    public enum Status {
        /**
         * 成功创建
         */
        SUCCESS,

        /**
         * 因置信度不足而跳过
         */
        SKIPPED_LOW_CONFIDENCE,

        /**
         * 因已存在相似技能而跳过
         */
        SKIPPED_EXISTS,

        /**
         * 创建失败
         */
        FAILED,

        /**
         * 正在处理
         */
        PENDING
    }
}
