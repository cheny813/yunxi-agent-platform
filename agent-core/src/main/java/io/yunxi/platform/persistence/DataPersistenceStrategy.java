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
 * @version 1.0.0
 */
public interface DataPersistenceStrategy {

    boolean saveConversation(ConversationEntity conversation);

    boolean deleteConversation(String conversationId);

    boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config);

    List<Msg> getMemory(String conversationId, MemoryConfig config);

    boolean deleteMemory(String conversationId);

    String getStrategyName();

    StrategyType getStrategyType();

    enum StrategyType {
        PRIMARY,
        CACHE,
        ARCHIVE,
        HYBRID
    }
}
