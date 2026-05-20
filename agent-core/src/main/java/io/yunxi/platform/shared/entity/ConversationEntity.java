package io.yunxi.platform.shared.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.agentscope.core.message.Msg;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话实体类，对应数据库 Conversations 表
 * 使用MyBatis映射，无需JPA注解
 *
 * @author yunxi-agent-platform
 */
@Data
public class ConversationEntity {

    /**
     * 会话ID
     */
    private String id;

    /**
     * Agent名称
     */
    private String agentName;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 消息列表（JSON格式存储）
     */
    private List<Msg> messages = new ArrayList<>();

    /**
     * 获取消息列表
     *
     * @return 消息列表
     */
    public List<Msg> getMessages() {
        return messages;
    }

    /**
     * 设置消息列表，同时更新消息数量
     *
     * @param messages 消息列表
     */
    public void setMessages(List<Msg> messages) {
        this.messages = messages;
        this.messageCount = messages != null ? messages.size() : 0;
    }

    /**
     * 消息数量
     */
    private Integer messageCount = 0;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    private LocalDateTime lastUpdatedAt;

    /**
     * 过期时间
     */
    private LocalDateTime expiresAt;

    /**
     * 检查会话是否过期
     *
     * @return true-已过期，false-未过期
     */
    @JsonIgnore
    public boolean isExpired() {
        if (expiresAt == null) {
            return false;
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * 延长过期时间
     *
     * @param hours 延长的小时数
     */
    public void extendExpiration(int hours) {
        if (expiresAt == null) {
            expiresAt = LocalDateTime.now().plusHours(hours);
        } else {
            expiresAt = expiresAt.plusHours(hours);
        }
    }

    /**
     * 添加消息到会话
     *
     * @param message 要添加的消息对象
     */
    public void addMessage(Msg message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
        this.messageCount = this.messages.size();
        this.lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * 插入前回调方法
     * 初始化时间戳和过期时间
     */
    public void preInsert() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.lastUpdatedAt == null) {
            this.lastUpdatedAt = now;
        }
        if (this.expiresAt == null) {
            this.expiresAt = now.plusHours(24); // 默认24小时过期
        }
    }

    /**
     * 更新前回调方法
     * 更新时间戳
     */
    public void preUpdate() {
        this.lastUpdatedAt = LocalDateTime.now();
    }
}
