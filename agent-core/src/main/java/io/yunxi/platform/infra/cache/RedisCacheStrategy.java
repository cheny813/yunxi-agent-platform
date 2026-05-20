package io.yunxi.platform.infra.cache;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.infra.persistence.DataPersistenceStrategy;
import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis 缓存策略
 * 
 * <p>
 * 将数据持久化到 Redis，适合：
 * <ul>
 *   <li>热数据缓存</li>
 *   <li>快速访问</li>
 *   <li>分布式场景</li>
 * </ul>
 * </p>
 * 
 * <p>
 * <b>特点</b>：
 * <ul>
 *   <li>自动过期（TTL）</li>
 *   <li>高并发</li>
 *   <li>不适合存储全量数据</li>
 * </ul>
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service("redisCacheStrategy")
public class RedisCacheStrategy implements DataPersistenceStrategy {

    /** Redis 缓存服务 */
    private final RedisCacheService redisCacheService;

    /**
     * 构造函数
     *
     * @param redisCacheService Redis 缓存服务
     */
    public RedisCacheStrategy(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    /**
     * 保存会话到 Redis 缓存
     *
     * @param conversation 会话实体
     * @return 是否保存成功
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) {
            return false;
        }
        
        redisCacheService.put(CacheNamespaces.CONVERSATION, conversation.getId(), conversation,
                Duration.ofHours(CacheNamespaces.DEFAULT_TTL_HOURS));
        
        log.debug("Redis 缓存保存会话: id={}", conversation.getId());
        return true;
    }

    /**
     * 从 Redis 缓存删除会话
     *
     * @param conversationId 会话ID
     * @return 是否删除成功
     */
    @Override
    public boolean deleteConversation(String conversationId) {
        return redisCacheService.delete(CacheNamespaces.CONVERSATION, conversationId);
    }

    /**
     * 保存记忆到 Redis 缓存
     *
     * @param conversationId 会话ID
     * @param messages       消息列表
     * @param config         记忆配置
     * @return 是否保存成功
     */
    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        if (conversationId == null || messages == null) {
            return false;
        }
        
        redisCacheService.put(CacheNamespaces.MEMORY, conversationId, messages,
                Duration.ofHours(CacheNamespaces.MEMORY_TTL_HOURS));
        redisCacheService.put(CacheNamespaces.MEMORY_CONFIG, conversationId, config,
                Duration.ofHours(CacheNamespaces.MEMORY_TTL_HOURS));
        
        log.debug("Redis 缓存保存记忆: conversationId={}, count={}", conversationId, messages.size());
        return true;
    }

    /**
     * 从 Redis 缓存获取记忆
     *
     * @param conversationId 会话ID
     * @param config         记忆配置
     * @return 消息列表
     */
    @Override
    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        if (conversationId == null) {
            return List.of();
        }
        
        // 根据配置决定返回多少条
        int maxSize = config.getMaxContextSize() > 0 ? config.getMaxContextSize() : 20;
        
        Optional<List<Msg>> result = redisCacheService.get(CacheNamespaces.MEMORY, conversationId,
                new com.fasterxml.jackson.core.type.TypeReference<List<Msg>>() {});
        
        if (result.isPresent() && result.get().size() > maxSize) {
            // 只返回最近的N条
            List<Msg> all = result.get();
            return all.subList(all.size() - maxSize, all.size());
        }
        
        return result.orElse(List.of());
    }

    /**
     * 从 Redis 缓存删除记忆
     *
     * @param conversationId 会话ID
     * @return 是否删除成功
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        redisCacheService.delete(CacheNamespaces.MEMORY, conversationId);
        redisCacheService.delete(CacheNamespaces.MEMORY_CONFIG, conversationId);
        return true;
    }

    /**
     * 获取策略名称
     *
     * @return 策略名称
     */
    @Override
    public String getStrategyName() {
        return "RedisCache";
    }

    /**
     * 获取策略类型
     *
     * @return 缓存策略类型
     */
    @Override
    public DataPersistenceStrategy.StrategyType getStrategyType() {
        return DataPersistenceStrategy.StrategyType.CACHE;
    }
}
