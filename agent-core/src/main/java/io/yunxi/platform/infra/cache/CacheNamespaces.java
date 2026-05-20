package io.yunxi.platform.infra.cache;

/**
 * 缓存命名空间常量
 * 
 * <p>
 * 定义 Redis 缓存的命名空间，用于区分不同类型的缓存数据
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public final class CacheNamespaces {

    private CacheNamespaces() {
        // 工具类，禁止实例化
    }

    /**
     * 会话缓存命名空间
     * <p>
     * Key: conversationId
     * Value: ConversationEntity
     * </p>
     */
    public static final String CONVERSATION = "conversation";

    /**
     * 会话记忆缓存命名空间
     * <p>
     * Key: conversationId
     * Value: List<Msg>
     * </p>
     */
    public static final String MEMORY = "memory";

    /**
     * 会话记忆配置缓存命名空间
     * <p>
     * Key: conversationId
     * Value: MemoryConfig
     * </p>
     */
    public static final String MEMORY_CONFIG = "memory_config";

    /**
     * Agent 配置缓存命名空间
     * <p>
     * Key: agentName
     * Value: AgentConfigDto
     * </p>
     */
    public static final String AGENT_CONFIG = "agent_config";

    /**
     * Agent 实例状态缓存命名空间
     * <p>
     * Key: agentName
     * Value: Agent 状态信息（不含实例）
     * </p>
     */
    public static final String AGENT_STATE = "agent_state";

    /**
     * 用户会话列表缓存命名空间
     * <p>
     * Key: userId
     * Value: List<ConversationInfoDto>
     * </p>
     */
    public static final String USER_CONVERSATIONS = "user_conversations";

    /**
     * 默认缓存过期时间（小时）
     */
    public static final long DEFAULT_TTL_HOURS = 24;
    /** 记忆缓存过期时间（小时），较短 */
    public static final long MEMORY_TTL_HOURS = 1;
    /** Agent配置缓存过期时间（小时），较长（7天） */
    public static final long AGENT_CONFIG_TTL_HOURS = 168;
}
