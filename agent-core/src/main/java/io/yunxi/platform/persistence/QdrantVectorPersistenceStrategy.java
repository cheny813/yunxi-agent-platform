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
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "qdrant.enabled", havingValue = "false")
public class QdrantVectorPersistenceStrategy implements DataPersistenceStrategy {

    public QdrantVectorPersistenceStrategy() {
    }

    @PostConstruct
    public void init() {
        log.warn("Qdrant 向量存储策略暂时禁用，请使用Milvus方案");
    }

    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        log.warn("Qdrant功能暂时禁用");
        return false;
    }

    @Override
    public boolean deleteConversation(String conversationId) {
        log.warn("Qdrant功能暂时禁用");
        return false;
    }

    @Override
    public boolean saveMemory(String conversationId, List<io.agentscope.core.message.Msg> messages,
            MemoryConfig config) {
        log.warn("Qdrant功能暂时禁用");
        return false;
    }

    @Override
    public List<io.agentscope.core.message.Msg> getMemory(String conversationId, MemoryConfig config) {
        return Collections.emptyList();
    }

    @Override
    public boolean deleteMemory(String conversationId) {
        log.warn("Qdrant功能暂时禁用");
        return false;
    }

    @Override
    public String getStrategyName() {
        return "QdrantVector(Disabled)";
    }

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.ARCHIVE;
    }
}
