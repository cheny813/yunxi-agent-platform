package io.yunxi.platform.persistence;

import java.util.List;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;

/**
 * 数据持久化策略接口
 *
 * <p>
 * 抽象不同数据持久化方式的统一接口，支持：
 * <ul>
 * <li>数据库持久化（MySQL）</li>
 * <li>缓存持久化（Redis）</li>
 * <li>向量数据库持久化（Milvus/Qdrant）</li>
 * <li>组合策略（多种存储同时进行）</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public interface DataPersistenceStrategy {

    /**
     * 保存会话数据。
     *
     * @param conversation 会话实体
     * @return 保存成功返回 true，否则返回 false
     */
    boolean saveConversation(ConversationEntity conversation);

    /**
     * 按会话 ID 删除会话。
     *
     * @param conversationId 会话标识
     * @return 删除成功返回 true，否则返回 false
     */
    boolean deleteConversation(String conversationId);

    /**
     * 保存会话记忆（消息列表）。
     *
     * @param conversationId 会话标识
     * @param messages 消息列表
     * @param config 记忆配置
     * @return 保存成功返回 true，否则返回 false
     */
    boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config);

    /**
     * 按会话 ID 获取记忆（消息列表）。
     *
     * @param conversationId 会话标识
     * @param config 记忆配置
     * @return 消息列表，未命中时返回空列表
     */
    List<Msg> getMemory(String conversationId, MemoryConfig config);

    /**
     * 删除指定会话的记忆。
     *
     * @param conversationId 会话标识
     * @return 删除成功返回 true，否则返回 false
     */
    boolean deleteMemory(String conversationId);

    /**
     * 返回策略名称标识。
     *
     * @return 策略名称字符串
     */
    String getStrategyName();

    /**
     * 返回策略类型（主/缓存/归档/混合）。
     *
     * @return 策略类型枚举
     */
    StrategyType getStrategyType();

    /**
     * 持久化策略类型枚举。
     */
    enum StrategyType {
        /** 主存储（如数据库） */
        PRIMARY,
        /** 缓存（如 Redis） */
        CACHE,
        /** 归档存储（如向量库） */
        ARCHIVE,
        /** 组合多种策略 */
        HYBRID
    }
}
