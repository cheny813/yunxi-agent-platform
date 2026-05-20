package io.yunxi.platform.shared.dto;

import java.time.Instant;

/**
 * 会话信息传输对象（DTO）
 *
 * <p>
 * 用于 API 响应中返回会话的基本信息，不包含完整的消息列表。
 * 适用于会话列表展示、会话概览等场景。
 * </p>
 *
 * <p>
 * 与 ConversationDto 实体的区别：
 * <ul>
 * <li>ConversationDto：包含完整的消息内容，用于内部逻辑处理</li>
 * <li>ConversationInfoDto：只包含会话元数据，用于 API 响应</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class ConversationInfoDto {

    /**
     * 会话 ID
     * <p>
     * 全局唯一标识符，使用 UUID 生成。
     * 用于获取会话详情、删除会话等操作。
     * </p>
     */
    private String id;

    /**
     * Agent 名称
     * <p>
     * 关联的 Agent 的唯一名称。
     * 标识此会话由哪个 Agent 处理。
     * </p>
     */
    private String agentName;

    /**
     * 用户 ID
     * <p>
     * 会话所属用户的标识符。
     * 可选字段，用于区分不同用户的会话。
     * 如果为 null 或空，表示匿名会话或未指定用户。
     * </p>
     */
    private String userId;

    /**
     * 会话标题
     * <p>
     * 会话的简要描述或名称。
     * 显示在会话列表中，帮助用户识别不同的会话。
     * 可以根据用户第一条消息自动生成，或由用户手动设置。
     * </p>
     */
    private String title;

    /**
     * 消息总数
     * <p>
     * 会话中包含的用户消息和 Assistant 消息的总数。
     * 用于快速统计会话活跃度。
     * 默认值：0
     * </p>
     */
    private int messageCount;

    /**
     * 创建时间
     * <p>
     * 会话创建的时间戳（UTC 时间）。
     * 使用 Instant 类型，便于跨时区处理。
     * </p>
     */
    private Instant createdAt;

    /**
     * 最后更新时间
     * <p>
     * 会话最后一条消息添加的时间戳（UTC 时间）。
     * 用于排序会话列表，最新的会话排在前面。
     * </p>
     */
    private Instant lastUpdatedAt;

    /**
     * 过期时间
     * <p>
     * 会话过期的时间戳（UTC 时间）。
     * 超过此时间的会话将被自动清理。
     * 默认过期时间：创建时间 + 24 小时
     * </p>
     */
    private Instant expiresAt;

    /**
     * 消息列表 JSON 字符串（用于 API 响应）
     */
    private String messages;

    /**
     * 默认构造函数
     * <p>
     * 创建一个空的 ConversationInfoDto 对象。
     * 所有字段初始值为 null 或 0。
     * </p>
     */
    public ConversationInfoDto() {
    }

    /**
     * 从 ConversationDto 实体构造 ConversationInfoDto
     * <p>
     * 自动转换字段类型：
     * <ul>
     * <li>LocalDateTime 转 Instant（用于 API 响应）</li>
     * </ul>
     * </p>
     *
     * @param conversationDto ConversationDto 实体对象
     */
    public ConversationInfoDto(ConversationDto conversationDto) {
        this.id = conversationDto.getId();
        this.agentName = conversationDto.getAgentName();
        this.userId = conversationDto.getUserId();
        this.title = conversationDto.getTitle();
        this.messageCount = conversationDto.getMessageCount();
        this.createdAt = conversationDto.getCreatedAt();
        this.lastUpdatedAt = conversationDto.getLastUpdatedAt();
        this.expiresAt = conversationDto.getExpiresAt();
    }

    // ==================== Getter 和 Setter 方法 ====================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getMessages() {
        return messages;
    }

    public void setMessages(String messages) {
        this.messages = messages;
    }
}
