package io.yunxi.platform.infra.persistence;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.infra.cache.RedisCacheStrategy;
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
 *
 * <p>
 * 组合多个策略，通过配置决定使用哪些持久化方式
 * </p>
 *
 * <p>
 * <b>配置示例</b>：
 * <pre>
 * persistence:
 *   enabled-strategies:
 *     - database     # 保存到数据库（必需）
 *     - redis        # 缓存到Redis（推荐）
 *     - milvus       # 保存到Milvus向量数据库（可选）
 *     - qdrant       # 保存到Qdrant向量数据库（可选）
 * </pre>
 * </p>
 *
 * <p>
 * <b>执行顺序</b>：
 * <ul>
 *   <li>PRIMARY 类型先执行（数据库）</li>
 *   <li>CACHE 类型后执行（Redis）</li>
 *   <li>ARCHIVE 类型最后执行（向量库）</li>
 *   <li>任何一个失败不影响其他策略</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@Primary  // 默认使用混合策略
public class HybridPersistenceStrategy implements DataPersistenceStrategy {

    /** 数据库持久化策略 */
    private final DatabasePersistenceStrategy databaseStrategy;
    /** Redis 缓存策略 */
    private final RedisCacheStrategy redisCacheStrategy;

    /** Milvus 向量策略（可选，仅在 Milvus 启用时注入） */
    private MilvusVectorPersistenceStrategy milvusStrategy;
    /** Qdrant 向量策略（可选，仅在 Qdrant 启用时注入） */
    private QdrantVectorPersistenceStrategy qdrantStrategy;

    /** 已启用的策略列表 */
    private final List<DataPersistenceStrategy> strategies = new ArrayList<>();

    /**
     * 构造混合持久化策略
     *
     * @param databaseStrategy  数据库持久化策略
     * @param redisCacheStrategy Redis 缓存策略
     */
    public HybridPersistenceStrategy(
            DatabasePersistenceStrategy databaseStrategy,
            RedisCacheStrategy redisCacheStrategy) {
        this.databaseStrategy = databaseStrategy;
        this.redisCacheStrategy = redisCacheStrategy;

        // 默认策略：数据库 + Redis
        this.strategies.add(databaseStrategy);
        this.strategies.add(redisCacheStrategy);

        log.info("混合持久化策略初始化完成，策略列表: {}", getStrategyNames());
    }

/**
     * 注入 Milvus 向量策略（可选）
     */
    @Autowired
    public void setMilvusStrategy(ObjectProvider<MilvusVectorPersistenceStrategy> milvusStrategyProvider) {
        MilvusVectorPersistenceStrategy milvusStrategy = milvusStrategyProvider.getIfAvailable();
        if (milvusStrategy != null) {
            this.milvusStrategy = milvusStrategy;
            this.strategies.add(milvusStrategy);
            log.info("Milvus 向量持久化策略已注入");
        }
    }

    /**
     * 注入 Qdrant 向量策略（可选）
     */
    @Autowired
    public void setQdrantStrategy(ObjectProvider<QdrantVectorPersistenceStrategy> qdrantStrategyProvider) {
        QdrantVectorPersistenceStrategy qdrantStrategy = qdrantStrategyProvider.getIfAvailable();
        if (qdrantStrategy != null) {
            this.qdrantStrategy = qdrantStrategy;
            this.strategies.add(qdrantStrategy);
            log.info("Qdrant 向量持久化策略已注入");
        }
    }

    /**
     * 配置启用的策略
     *
     * @param strategyNames 策略名称列表
     */
    public void setEnabledStrategies(List<String> strategyNames) {
        this.strategies.clear();
        
        for (String name : strategyNames) {
            DataPersistenceStrategy strategy = findStrategy(name);
            if (strategy != null) {
                this.strategies.add(strategy);
                log.info("启用持久化策略: {}", name);
            } else {
                log.warn("未找到策略: {}", name);
            }
        }
        
        // 确保至少有一个 PRIMARY 策略
        boolean hasPrimary = strategies.stream()
                .anyMatch(s -> s.getStrategyType() == StrategyType.PRIMARY);
        if (!hasPrimary && strategies.stream().noneMatch(s -> s instanceof DatabasePersistenceStrategy)) {
            log.warn("没有 PRIMARY 策略，添加数据库策略作为默认");
            strategies.add(0, databaseStrategy);
        }
        
        log.info("持久化策略配置完成: {}", getStrategyNames());
    }

    /**
     * 根据名称查找策略
     *
     * @param name 策略名称
     * @return 对应的持久化策略，未找到返回 null
     */
    private DataPersistenceStrategy findStrategy(String name) {
        return switch (name.toLowerCase()) {
            case "database" -> databaseStrategy;
            case "redis", "cache" -> redisCacheStrategy;
            case "milvus", "vector" -> milvusStrategy;
            case "qdrant" -> qdrantStrategy;
            default -> null;
        };
    }

    /**
     * 保存会话（任一策略成功即返回成功）
     *
     * @param conversation 会话实体
     * @return 任一策略保存成功返回 true
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        boolean anySuccess = false;
        
        for (DataPersistenceStrategy strategy : strategies) {
            try {
                boolean success = strategy.saveConversation(conversation);
                if (success) {
                    anySuccess = true;
                    log.debug("策略保存成功: {}", strategy.getStrategyName());
                }
            } catch (Exception e) {
                log.error("策略保存失败: {}, error={}", strategy.getStrategyName(), e.getMessage());
            }
        }
        
        return anySuccess;
    }

    /**
     * 删除会话（所有策略都成功才返回成功）
     *
     * @param conversationId 会话 ID
     * @return 所有策略删除成功返回 true
     */
    @Override
    public boolean deleteConversation(String conversationId) {
        boolean allSuccess = true;
        
        for (DataPersistenceStrategy strategy : strategies) {
            try {
                boolean success = strategy.deleteConversation(conversationId);
                if (!success) {
                    allSuccess = false;
                }
            } catch (Exception e) {
                log.error("策略删除失败: {}, error={}", strategy.getStrategyName(), e.getMessage());
                allSuccess = false;
            }
        }
        
        return allSuccess;
    }

    /**
     * 保存记忆（任一策略成功即返回成功）
     *
     * @param conversationId 会话 ID
     * @param messages       消息列表
     * @param config         记忆配置
     * @return 任一策略保存成功返回 true
     */
    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        boolean anySuccess = false;
        
        for (DataPersistenceStrategy strategy : strategies) {
            try {
                boolean success = strategy.saveMemory(conversationId, messages, config);
                if (success) {
                    anySuccess = true;
                    log.debug("策略保存记忆成功: {}", strategy.getStrategyName());
                }
            } catch (Exception e) {
                log.error("策略保存记忆失败: {}, error={}", strategy.getStrategyName(), e.getMessage());
            }
        }
        
        return anySuccess;
    }

    /**
     * 获取记忆（按优先级：缓存 -> 主存储 -> 归档）
     *
     * @param conversationId 会话 ID
     * @param config         记忆配置
     * @return 消息列表
     */
    @Override
    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        // 按优先级顺序查找：CACHE -> PRIMARY -> ARCHIVE
        
        // 1. 先查缓存（最快）
        for (DataPersistenceStrategy strategy : strategies) {
            if (strategy.getStrategyType() == StrategyType.CACHE) {
                try {
                    List<Msg> result = strategy.getMemory(conversationId, config);
                    if (!result.isEmpty()) {
                        log.debug("从缓存获取记忆: {}, count={}", strategy.getStrategyName(), result.size());
                        return result;
                    }
                } catch (Exception e) {
                    log.warn("从缓存获取记忆失败: {}, error={}", strategy.getStrategyName(), e.getMessage());
                }
            }
        }
        
        // 2. 再查主存储（数据库）
        for (DataPersistenceStrategy strategy : strategies) {
            if (strategy.getStrategyType() == StrategyType.PRIMARY) {
                try {
                    List<Msg> result = strategy.getMemory(conversationId, config);
                    log.debug("从主存储获取记忆: {}, count={}", strategy.getStrategyName(), result.size());
                    return result;
                } catch (Exception e) {
                    log.error("从主存储获取记忆失败: {}, error={}", strategy.getStrategyName(), e.getMessage());
                }
            }
        }
        
        // 3. 最后查归档（向量库，如果启用）
        for (DataPersistenceStrategy strategy : strategies) {
            if (strategy.getStrategyType() == StrategyType.ARCHIVE) {
                try {
                    List<Msg> result = strategy.getMemory(conversationId, config);
                    log.debug("从归档获取记忆: {}, count={}", strategy.getStrategyName(), result.size());
                    return result;
                } catch (Exception e) {
                    log.warn("从归档获取记忆失败: {}, error={}", strategy.getStrategyName(), e.getMessage());
                }
            }
        }
        
        return List.of();
    }

    /**
     * 删除记忆（所有策略都成功才返回成功）
     *
     * @param conversationId 会话 ID
     * @return 所有策略删除成功返回 true
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        boolean allSuccess = true;
        
        for (DataPersistenceStrategy strategy : strategies) {
            try {
                boolean success = strategy.deleteMemory(conversationId);
                if (!success) {
                    allSuccess = false;
                }
            } catch (Exception e) {
                log.error("策略删除记忆失败: {}, error={}", strategy.getStrategyName(), e.getMessage());
                allSuccess = false;
            }
        }
        
        return allSuccess;
    }

    /**
     * 获取策略名称
     *
     * @return 混合策略名称，包含所有子策略
     */
    @Override
    public String getStrategyName() {
        return "Hybrid(" + getStrategyNames() + ")";
    }

    /**
     * 获取策略类型
     *
     * @return HYBRID 混合类型
     */
    @Override
    public StrategyType getStrategyType() {
        return StrategyType.HYBRID;
    }

    /**
     * 获取所有已启用策略的名称拼接
     *
     * @return 策略名称逗号分隔字符串
     */
    private String getStrategyNames() {
        return strategies.stream()
                .map(DataPersistenceStrategy::getStrategyName)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}

