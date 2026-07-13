package io.yunxi.platform.persistence;

import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Qdrant 向量数据库持久化策略（当前暂时禁用）
 *
 * <p>
 * Qdrant Java Client 1.7.0 API与预期不完全兼容，当前为占位符，生产环境建议使用Milvus方案。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "qdrant.enabled", havingValue = "false")
public class QdrantVectorPersistenceStrategy implements DataPersistenceStrategy {

    public QdrantVectorPersistenceStrategy() {
    }

    /**
     * 启动初始化，提示该策略当前被禁用。
     */
    @PostConstruct
    public void init() {
        log.warn("Qdrant 向量存储策略暂时禁用，请使用Milvus方案");
    }

    /**
     * 禁用占位：保存会话始终返回失败。
     *
     * @param conversation 会话实体
     * @return 恒为 false
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        log.warn("Qdrant功能暂时禁用");
        return false;
    }

    /**
     * 禁用占位：删除会话始终返回失败。
     *
     * @param conversationId 会话标识
     * @return 恒为 false
     */
    @Override
    public boolean deleteConversation(String conversationId) {
        log.warn("Qdrant功能暂时禁用");
        return false;
    }

    /**
     * 禁用占位：保存记忆始终返回失败。
     *
     * @param conversationId 会话标识
     * @param messages 消息列表
     * @param config 记忆配置
     * @return 恒为 false
     */
    @Override
    public boolean saveMemory(String conversationId, List<io.agentscope.core.message.Msg> messages,
            MemoryConfig config) {
        log.warn("Qdrant功能暂时禁用");
        return false;
    }

    /**
     * 禁用占位：获取记忆返回空列表。
     *
     * @param conversationId 会话标识
     * @param config 记忆配置
     * @return 空列表
     */
    @Override
    public List<io.agentscope.core.message.Msg> getMemory(String conversationId, MemoryConfig config) {
        return Collections.emptyList();
    }

    /**
     * 禁用占位：删除记忆始终返回失败。
     *
     * @param conversationId 会话标识
     * @return 恒为 false
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        log.warn("Qdrant功能暂时禁用");
        return false;
    }

    /**
     * 返回策略名称。
     *
     * @return 固定字符串 {@code "QdrantVector(Disabled)"}
     */
    @Override
    public String getStrategyName() {
        return "QdrantVector(Disabled)";
    }

    /**
     * 返回策略类型。
     *
     * @return 归档策略 {@link StrategyType#ARCHIVE}
     */
    @Override
    public StrategyType getStrategyType() {
        return StrategyType.ARCHIVE;
    }
}
