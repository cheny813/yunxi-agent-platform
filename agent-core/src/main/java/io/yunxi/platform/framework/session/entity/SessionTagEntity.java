package io.yunxi.platform.framework.session.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话标签实体类
 * <p>
 * 存储会话的标签和分类信息，支持MySQL FULLTEXT全文搜索
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Data
public class SessionTagEntity {

    /**
     * 标签ID
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
     * 标签类型（topic, domain, sentiment, custom）
     */
    private String tagType;

    /**
     * 标签名称
     */
    private String tagName;

    /**
     * 标签值
     */
    private String tagValue;

    /**
     * 置信度（0-1）
     */
    private Double confidence;

    /**
     * 权重（用于排序）
     */
    private Integer weight;

    /**
     * 额外元数据（JSON格式）
     */
    private String metadata;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
