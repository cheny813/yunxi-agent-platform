package io.yunxi.platform.cache;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.persistence.DataPersistenceStrategy;
import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis 缓存策略
 * <p>将数据持久化到 Redis，适合热数据缓存、快速访问、分布式场景。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service("redisCacheStrategy")
public class RedisCacheStrategy implements DataPersistenceStrategy {

    private final RedisCacheService redisCacheService;

    /**
     * 构造基于 Redis 的缓存持久化策略。
     *
     * @param redisCacheService Redis 缓存服务（由 Spring 注入）
     */
    public RedisCacheStrategy(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    /**
     * 保存会话到 Redis 缓存。
     *
     * @param conversation 会话实体（ID 不能为空）
     * @return 保存成功返回 true，参数非法时返回 false
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) return false;
        redisCacheService.put(CacheNamespaces.CONVERSATION, conversation.getId(), conversation,
                Duration.ofHours(CacheNamespaces.DEFAULT_TTL_HOURS));
        return true;
    }

    /**
     * 从 Redis 缓存删除会话。
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    @Override
    public boolean deleteConversation(String conversationId) {
        return redisCacheService.delete(CacheNamespaces.CONVERSATION, conversationId);
    }

    /**
     * 保存会话记忆与记忆配置到 Redis 缓存。
     *
     * @param conversationId 会话 ID
     * @param messages       记忆消息列表
     * @param config         记忆配置
     * @return 保存成功返回 true，会话 ID 或消息为空时返回 false
     */
    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        if (conversationId == null || messages == null) return false;
        redisCacheService.put(CacheNamespaces.MEMORY, conversationId, messages,
                Duration.ofHours(CacheNamespaces.MEMORY_TTL_HOURS));
        redisCacheService.put(CacheNamespaces.MEMORY_CONFIG, conversationId, config,
                Duration.ofHours(CacheNamespaces.MEMORY_TTL_HOURS));
        return true;
    }

    /**
     * 从 Redis 缓存读取会话记忆，并按配置的最大上下文大小截断。
     *
     * @param conversationId 会话 ID
     * @param config         记忆配置（提供最大上下文条数上限）
     * @return 记忆消息列表，未命中或参数为空时返回空列表
     */
    @Override
    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        if (conversationId == null) return List.of();
        int maxSize = config.getMaxContextSize() > 0 ? config.getMaxContextSize() : 20;
        Optional<List<Msg>> result = redisCacheService.get(CacheNamespaces.MEMORY, conversationId,
                new com.fasterxml.jackson.core.type.TypeReference<List<Msg>>() {});
        if (result.isPresent() && result.get().size() > maxSize) {
            List<Msg> all = result.get();
            return all.subList(all.size() - maxSize, all.size());
        }
        return result.orElse(List.of());
    }

    /**
     * 从 Redis 缓存删除会话记忆及其配置。
     *
     * @param conversationId 会话 ID
     * @return 始终返回 true
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        redisCacheService.delete(CacheNamespaces.MEMORY, conversationId);
        redisCacheService.delete(CacheNamespaces.MEMORY_CONFIG, conversationId);
        return true;
    }

    /**
     * 返回持久化策略名称。
     *
     * @return 策略名称 {@code "RedisCache"}
     */
    @Override public String getStrategyName() { return "RedisCache"; }

    /**
     * 返回持久化策略类型。
     *
     * @return 策略类型 {@link DataPersistenceStrategy.StrategyType#CACHE}
     */
    @Override public DataPersistenceStrategy.StrategyType getStrategyType() { return DataPersistenceStrategy.StrategyType.CACHE; }
}
