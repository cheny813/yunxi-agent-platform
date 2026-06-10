package io.yunxi.platform.cache;

/**
 * 缓存命名空间常量
 * <p>定义 Redis 缓存的命名空间，用于区分不同类型的缓存数据。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public final class CacheNamespaces {

    private CacheNamespaces() {}

    /** 会话缓存：Key=conversationId, Value=ConversationEntity */
    public static final String CONVERSATION = "conversation";
    /** 会话记忆缓存：Key=conversationId, Value=List<Msg> */
    public static final String MEMORY = "memory";
    /** 记忆配置缓存：Key=conversationId, Value=MemoryConfig */
    public static final String MEMORY_CONFIG = "memory_config";
    /** Agent 配置缓存：Key=agentName, Value=AgentConfigDto */
    public static final String AGENT_CONFIG = "agent_config";
    /** Agent 状态缓存：Key=agentName, Value=状态信息 */
    public static final String AGENT_STATE = "agent_state";
    /** 用户会话列表缓存：Key=userId, Value=List<ConversationInfoDto> */
    public static final String USER_CONVERSATIONS = "user_conversations";

    /** 默认缓存过期时间（24小时） */
    public static final long DEFAULT_TTL_HOURS = 24;
    /** 记忆缓存过期时间（1小时） */
    public static final long MEMORY_TTL_HOURS = 1;
    /** Agent 配置缓存过期时间（7天） */
    public static final long AGENT_CONFIG_TTL_HOURS = 168;
}
