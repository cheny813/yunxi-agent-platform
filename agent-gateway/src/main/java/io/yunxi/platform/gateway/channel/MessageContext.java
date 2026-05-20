package io.yunxi.platform.gateway.channel;

import io.yunxi.platform.gateway.model.PlatformType;

import java.util.Map;

/**
 * 统一消息上下文
 *
 * <p>封装从消息平台接收到的消息元信息，与具体平台解耦</p>
 */
public class MessageContext {

    private final PlatformType platform;
    private final String chatId;
    private final String chatType;      // "dm" / "group" / "channel"
    private final String userId;
    private final String userName;
    private final String threadId;      // 子话题 ID（可选）
    private final String sessionKey;    // 网关会话键
    private final Map<String, Object> extra;

    private MessageContext(Builder builder) {
        this.platform = builder.platform;
        this.chatId = builder.chatId;
        this.chatType = builder.chatType;
        this.userId = builder.userId;
        this.userName = builder.userName;
        this.threadId = builder.threadId;
        this.sessionKey = builder.sessionKey;
        this.extra = builder.extra;
    }

    public PlatformType getPlatform() { return platform; }
    public String getChatId() { return chatId; }
    public String getChatType() { return chatType; }
    public String getUserId() { return userId; }
    public String getUserName() { return userName; }
    public String getThreadId() { return threadId; }
    public String getSessionKey() { return sessionKey; }
    public Map<String, Object> getExtra() { return extra; }

    /**
     * 生成用于 agent-core 的 userId（格式：platform:platformUserId）
     */
    public String getAgentUserId() {
        return platform.getCode() + ":" + (userId != null ? userId : chatId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PlatformType platform;
        private String chatId;
        private String chatType = "dm";
        private String userId;
        private String userName;
        private String threadId;
        private String sessionKey;
        private Map<String, Object> extra;

        public Builder platform(PlatformType platform) { this.platform = platform; return this; }
        public Builder chatId(String chatId) { this.chatId = chatId; return this; }
        public Builder chatType(String chatType) { this.chatType = chatType; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder userName(String userName) { this.userName = userName; return this; }
        public Builder threadId(String threadId) { this.threadId = threadId; return this; }
        public Builder sessionKey(String sessionKey) { this.sessionKey = sessionKey; return this; }
        public Builder extra(Map<String, Object> extra) { this.extra = extra; return this; }
        public MessageContext build() { return new MessageContext(this); }
    }
}
