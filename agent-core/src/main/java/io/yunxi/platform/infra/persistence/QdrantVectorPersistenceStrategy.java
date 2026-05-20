package io.yunxi.platform.infra.persistence;

import io.agentscope.core.message.Msg;
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
 * 注意：Qdrant Java Client 1.7.0 API与预期不完全兼容
 * 当前实现为占位符，生产环境建议使用Milvus方案
 * </p>
 *
 * <p>
 * TODO: 待Qdrant Java Client API稳定后重新实现
 * 参考资料：https://github.com/qdrant/qdrant-java
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "qdrant.enabled", havingValue = "false") // 默认禁用
public class QdrantVectorPersistenceStrategy implements DataPersistenceStrategy {

    /**
     * 无参构造函数（当前功能禁用，不需要任何依赖）
     */
    public QdrantVectorPersistenceStrategy() {
        // 无参构造函数，不需要任何依赖
    }

    /**
     * 初始化（当前禁用，输出警告日志）
     */
    @PostConstruct
    public void init() {
        log.warn("Qdrant 向量存储策略暂时禁用，请使用Milvus方案");
    }

    /**
     * 保存会话（当前禁用）
     *
     * @param conversation 会话实体
     * @return 始终返回 false
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        log.warn("Qdrant功能暂时禁用，请启用Milvus向量存储");
        return false;
    }

    /**
     * 删除会话（当前禁用）
     *
     * @param conversationId 会话 ID
     * @return 始终返回 false
     */
    @Override
    public boolean deleteConversation(String conversationId) {
        log.warn("Qdrant功能暂时禁用，请启用Milvus向量存储");
        return false;
    }

    /**
     * 保存记忆（当前禁用）
     *
     * @param conversationId 会话 ID
     * @param messages       消息列表
     * @param memConfig      记忆配置
     * @return 始终返回 false
     */
    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig memConfig) {
        log.warn("Qdrant功能暂时禁用，请启用Milvus向量存储");
        return false;
    }

    /**
     * 获取记忆（当前禁用）
     *
     * @param conversationId 会话 ID
     * @param memConfig      记忆配置
     * @return 空列表
     */
    @Override
    public List<Msg> getMemory(String conversationId, MemoryConfig memConfig) {
        return Collections.emptyList();
    }

    /**
     * 删除记忆（当前禁用）
     *
     * @param conversationId 会话 ID
     * @return 始终返回 false
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        log.warn("Qdrant功能暂时禁用，请启用Milvus向量存储");
        return false;
    }

    /**
     * 获取策略名称
     *
     * @return 策略名称 "QdrantVector(Disabled)"
     */
    @Override
    public String getStrategyName() {
        return "QdrantVector(Disabled)";
    }

    /**
     * 获取策略类型
     *
     * @return ARCHIVE 归档类型
     */
    @Override
    public StrategyType getStrategyType() {
        return StrategyType.ARCHIVE;
    }
}

