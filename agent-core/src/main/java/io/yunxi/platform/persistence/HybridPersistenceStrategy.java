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
 * @version 1.0.0
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

    @Autowired
    public void setMilvusStrategy(ObjectProvider<MilvusVectorPersistenceStrategy> milvusStrategyProvider) {
        MilvusVectorPersistenceStrategy s = milvusStrategyProvider.getIfAvailable();
        if (s != null) {
            this.milvusStrategy = s;
            this.strategies.add(s);
            log.info("Milvus 策略已注入");
        }
    }

    @Autowired
    public void setQdrantStrategy(ObjectProvider<QdrantVectorPersistenceStrategy> qdrantStrategyProvider) {
        QdrantVectorPersistenceStrategy s = qdrantStrategyProvider.getIfAvailable();
        if (s != null) {
            this.qdrantStrategy = s;
            this.strategies.add(s);
            log.info("Qdrant 策略已注入");
        }
    }

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

    @Override
    public String getStrategyName() {
        return "Hybrid";
    }

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.HYBRID;
    }
}
