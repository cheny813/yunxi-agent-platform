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
 * @version 1.0.0
 */
@Slf4j
@Service("redisCacheStrategy")
public class RedisCacheStrategy implements DataPersistenceStrategy {

    private final RedisCacheService redisCacheService;

    public RedisCacheStrategy(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) return false;
        redisCacheService.put(CacheNamespaces.CONVERSATION, conversation.getId(), conversation,
                Duration.ofHours(CacheNamespaces.DEFAULT_TTL_HOURS));
        return true;
    }

    @Override
    public boolean deleteConversation(String conversationId) {
        return redisCacheService.delete(CacheNamespaces.CONVERSATION, conversationId);
    }

    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        if (conversationId == null || messages == null) return false;
        redisCacheService.put(CacheNamespaces.MEMORY, conversationId, messages,
                Duration.ofHours(CacheNamespaces.MEMORY_TTL_HOURS));
        redisCacheService.put(CacheNamespaces.MEMORY_CONFIG, conversationId, config,
                Duration.ofHours(CacheNamespaces.MEMORY_TTL_HOURS));
        return true;
    }

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

    @Override
    public boolean deleteMemory(String conversationId) {
        redisCacheService.delete(CacheNamespaces.MEMORY, conversationId);
        redisCacheService.delete(CacheNamespaces.MEMORY_CONFIG, conversationId);
        return true;
    }

    @Override public String getStrategyName() { return "RedisCache"; }
    @Override public DataPersistenceStrategy.StrategyType getStrategyType() { return DataPersistenceStrategy.StrategyType.CACHE; }
}
