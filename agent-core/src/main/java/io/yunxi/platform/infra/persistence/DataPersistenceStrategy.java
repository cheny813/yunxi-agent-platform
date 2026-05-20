package io.yunxi.platform.infra.persistence;

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
 *   <li>数据库持久化（MySQL）</li>
 *   <li>缓存持久化（Redis）</li>
 *   <li>向量数据库持久化（Milvus/Qdrant）</li>
 *   <li>组合策略（多种存储同时进行）</li>
 * </ul>
 * </p>
 * 
 * <p>
 * <b>配置驱动</b>：通过配置文件选择使用哪种策略，无需修改代码
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface DataPersistenceStrategy {

    /**
     * 保存会话
     *
     * @param conversation 会话实体
     * @return 保存成功返回 true
     */
    boolean saveConversation(ConversationEntity conversation);

    /**
     * 删除会话
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    boolean deleteConversation(String conversationId);

    /**
     * 保存会话记忆
     *
     * @param conversationId 会话 ID
     * @param messages      消息列表
     * @param config        记忆配置
     * @return 保存成功返回 true
     */
    boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config);

    /**
     * 获取会话记忆
     *
     * @param conversationId 会话 ID
     * @param config        记忆配置
     * @return 消息列表
     */
    List<Msg> getMemory(String conversationId, MemoryConfig config);

    /**
     * 删除会话记忆
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    boolean deleteMemory(String conversationId);

    /**
     * 策略名称
     *
     * @return 策略标识符
     */
    String getStrategyName();

    /**
     * 策略类型
     *
     * @return PRIMARY（主存储）、CACHE（缓存）、ARCHIVE（归档）或 HYBRID（混合）
     */
    StrategyType getStrategyType();

    /**
     * 策略类型枚举
     */
    enum StrategyType {
        /**
         * 主存储（数据最终存储位置，如MySQL）
         */
        PRIMARY,
        /**
         * 缓存（临时存储，如Redis）
         */
        CACHE,
        /**
         * 归档（长期存储，如向量数据库、对象存储）
         */
        ARCHIVE,
        /**
         * 混合（组合多种策略）
         */
        HYBRID
    }
}

