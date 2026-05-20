package io.yunxi.platform.framework.session.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话摘要实体类
 * <p>
 * 存储会话的LLM生成摘要，用于快速检索和上下文恢复
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Data
public class SessionSummaryEntity {

    /**
     * 摘要ID
     */
    private Long id;

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * Agent名称
     */
    private String agentName;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话摘要内容（LLM生成）
     */
    private String summary;

    /**
     * 摘要类型（session_overview, key_points, outcomes）
     */
    private String summaryType;

    /**
     * 关键词提取（JSON格式）
     */
    private String keywords;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 标签（JSON格式）
     */
    private String tags;

    /**
     * 消息数量
     */
    private Integer messageCount;

    /**
     * Token数量（估算）
     */
    private Integer tokenCount;

    /**
     * 摘要生成时间
     */
    private LocalDateTime generatedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
