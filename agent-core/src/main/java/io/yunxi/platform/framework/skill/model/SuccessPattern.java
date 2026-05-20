package io.yunxi.platform.framework.skill.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成功模式
 * <p>
 * 描述一个成功的对话模式，可以从中提取出可复用的技能
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Data
public class SuccessPattern {

    /**
     * 模式ID
     */
    private String id;

    /**
     * 模式名称
     */
    private String name;

    /**
     * 模式描述
     */
    private String description;

    /**
     * 触发条件
     */
    private String triggerCondition;

    /**
     * 关键步骤（从对话中提取）
     */
    private List<String> keySteps;

    /**
     * 使用示例
     */
    private String example;

    /**
     * 成功指标（如用户满意度、任务完成率等）
     */
    private Double successMetric;

    /**
     * 模式类型（如 instruction_following, reasoning, tool_use 等）
     */
    private String patternType;

    /**
     * 出现频率
     */
    private int frequency;

    /**
     * 置信度（0-1）
     */
    private double confidence;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt;
}
