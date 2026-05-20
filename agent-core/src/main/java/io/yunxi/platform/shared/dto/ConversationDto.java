package io.yunxi.platform.shared.dto;

import io.agentscope.core.message.Msg;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话实体 DTO
 *
 * <p>
 * 用于维护多轮对话的上下文历史。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class ConversationDto {

    /**
     * 会话 ID（UUID 格式）
     */
    private String id;

    /**
     * 关联的 Agent 名称
     */
    private String agentName;

    /**
     * 用户 ID（多租户场景）
     */
    private String userId;

    /**
     * 对话历史消息列表
     */
    private List<Msg> messages;

    /**
     * 创建时间
     */
    private Instant createdAt;

    /**
     * 最后更新时间
     */
    private Instant lastUpdatedAt;

    /**
     * 过期时间（用于自动清理）
     */
    private Instant expiresAt;

    /**
     * 会话标题（自动生成或用户指定）
     */
    private String title;

    /**
     * 默认构造函数
     * <p>
     * 初始化消息列表和时间戳
     * </p>
     */
    public ConversationDto() {
        this.messages = new ArrayList<>();
        this.createdAt = Instant.now();
        this.lastUpdatedAt = Instant.now();
    }

    /**
     * 带参构造函数
     *
     * @param id        会话 ID
     * @param agentName Agent 名称
     * @param userId    用户 ID
     */
    public ConversationDto(String id, String agentName, String userId) {
        this();
        this.id = id;
        this.agentName = agentName;
        this.userId = userId;
        // 默认过期时间为 24 小时
        this.expiresAt = Instant.now().plusSeconds(86400);
    }

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

    public List<Msg> getMessages() {
        return messages;
    }

    public void setMessages(List<Msg> messages) {
        this.messages = messages;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 添加消息到会话
     *
     * @param message 要添加的消息对象
     */
    public void addMessage(Msg message) {
        this.messages.add(message);
        this.lastUpdatedAt = Instant.now();
    }

    /**
     * 获取消息数量
     *
     * @return 消息数量
     */
    public int getMessageCount() {
        return messages.size();
    }

    /**
     * 检查会话是否已过期
     *
     * @return true-已过期，false-未过期
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * 更新过期时间（延长会话）
     *
     * @param hours 延长的小时数
     */
    public void extendExpiration(int hours) {
        if (this.expiresAt == null) {
            this.expiresAt = Instant.now();
        }
        this.expiresAt = this.expiresAt.plusSeconds(hours * 3600);
        this.lastUpdatedAt = Instant.now();
    }
}
