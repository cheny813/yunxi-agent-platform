package io.yunxi.platform.persistence;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.cache.RedisCacheStrategy;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合持久化策略（可配置）
 * <p>
 * 组合多个策略，通过配置决定使用哪些持久化方式。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
@Primary
public class HybridPersistenceStrategy implements DataPersistenceStrategy {

    private MilvusVectorPersistenceStrategy milvusStrategy;
    private QdrantVectorPersistenceStrategy qdrantStrategy;
    private final List<DataPersistenceStrategy> strategies = new ArrayList<>();

    public HybridPersistenceStrategy(DatabasePersistenceStrategy databaseStrategy,
            RedisCacheStrategy redisCacheStrategy) {
        this.strategies.add(databaseStrategy);
        this.strategies.add(redisCacheStrategy);
        log.info("混合持久化策略初始化完成");
    }

    /**
     * 注入可选 Milvus 向量策略（存在且启用时加入策略链）。
     *
     * @param milvusStrategyProvider Milvus 策略的 ObjectProvider（条件装配，可能不存在）
     */
    @Autowired
    public void setMilvusStrategy(ObjectProvider<MilvusVectorPersistenceStrategy> milvusStrategyProvider) {
        MilvusVectorPersistenceStrategy s = milvusStrategyProvider.getIfAvailable();
        if (s != null) {
            this.milvusStrategy = s;
            this.strategies.add(s);
            log.info("Milvus 策略已注入");
        }
    }

    /**
     * 注入可选 Qdrant 向量策略（存在且启用时加入策略链）。
     *
     * @param qdrantStrategyProvider Qdrant 策略的 ObjectProvider（条件装配，可能不存在）
     */
    @Autowired
    public void setQdrantStrategy(ObjectProvider<QdrantVectorPersistenceStrategy> qdrantStrategyProvider) {
        QdrantVectorPersistenceStrategy s = qdrantStrategyProvider.getIfAvailable();
        if (s != null) {
            this.qdrantStrategy = s;
            this.strategies.add(s);
            log.info("Qdrant 策略已注入");
        }
    }

    /**
     * 依次调用各子策略保存会话，任一成功即视为成功（容错）。
     *
     * @param conversation 会话实体
     * @return 任一子策略成功返回 true，全部失败返回 false
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        boolean anySuccess = false;
        for (DataPersistenceStrategy s : strategies) {
            try {
                if (s.saveConversation(conversation))
                    anySuccess = true;
            } catch (Exception e) {
                log.error("策略保存失败: {}", s.getStrategyName(), e);
            }
        }
        return anySuccess;
    }

    /**
     * 依次调用各子策略删除会话，要求全部成功。
     *
     * @param conversationId 会话标识
     * @return 所有子策略均成功返回 true，存在失败返回 false
     */
    @Override
    public boolean deleteConversation(String conversationId) {
        boolean allSuccess = true;
        for (DataPersistenceStrategy s : strategies) {
            try {
                if (!s.deleteConversation(conversationId))
                    allSuccess = false;
            } catch (Exception e) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    /**
     * 依次调用各子策略保存记忆，任一成功即视为成功（容错）。
     *
     * @param conversationId 会话标识
     * @param messages 消息列表
     * @param config 记忆配置
     * @return 任一子策略成功返回 true，全部失败返回 false
     */
    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        boolean anySuccess = false;
        for (DataPersistenceStrategy s : strategies) {
            try {
                if (s.saveMemory(conversationId, messages, config))
                    anySuccess = true;
            } catch (Exception e) {
                log.error("策略保存记忆失败: {}", s.getStrategyName(), e);
            }
        }
        return anySuccess;
    }

    /**
     * 遍历子策略获取记忆，返回首个非空结果。
     *
     * @param conversationId 会话标识
     * @param config 记忆配置
     * @return 首个子策略返回的非空消息列表，全部为空时返回空列表
     */
    @Override
    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        for (DataPersistenceStrategy s : strategies) {
            try {
                List<Msg> r = s.getMemory(conversationId, config);
                if (!r.isEmpty())
                    return r;
            } catch (Exception e) {
                log.warn("取记忆失败: {}", s.getStrategyName(), e);
            }
        }
        return List.of();
    }

    /**
     * 依次调用各子策略删除记忆，要求全部成功。
     *
     * @param conversationId 会话标识
     * @return 所有子策略均成功返回 true，存在失败返回 false
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        boolean allSuccess = true;
        for (DataPersistenceStrategy s : strategies) {
            try {
                if (!s.deleteMemory(conversationId))
                    allSuccess = false;
            } catch (Exception e) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    /**
     * 返回策略名称。
     *
     * @return 固定字符串 {@code "Hybrid"}
     */
    @Override
    public String getStrategyName() {
        return "Hybrid";
    }

    /**
     * 返回策略类型。
     *
     * @return 混合策略 {@link StrategyType#HYBRID}
     */
    @Override
    public StrategyType getStrategyType() {
        return StrategyType.HYBRID;
    }
}
