package io.yunxi.platform.framework.agent;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 配置缓存服务
 * <p>
 * 负责缓存支持高级功能的 Agent 实例，避免每次请求都创建新的 Agent
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Service
public class AgentConfigDtoCache {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigDtoCache.class);

    private final Map<String, CachedAgent> agentCache = new ConcurrentHashMap<>();

    private static final int MAX_CACHE_SIZE = 100;

    private static final long CACHE_TTL = 30 * 60 * 1000; // 30 分钟

    /**
     * 获取或创建配置的 Agent
     */
    public Agent getOrCreateAgent(String baseAgentName,
            UnifiedChatRequest request,
            AdvancedAgentFactory factory) {
        String configKey = generateConfigKey(baseAgentName, request);

        CachedAgent cached = agentCache.get(configKey);
        if (cached != null && !isExpired(cached)) {
            log.info("从缓存获取 Agent: key={}", configKey);
            cached.lastAccessTime = System.currentTimeMillis();
            return cached.agent;
        }

        log.info("创建新 Agent: key={}", configKey);
        Agent agent = factory.createTempAgent(baseAgentName, request);

        if (agent == null) {
            log.warn("Agent 创建失败，使用基础 Agent: baseAgent={}", baseAgentName);
            return null;
        }

        if (agentCache.size() >= MAX_CACHE_SIZE) {
            cleanupExpired();
        }

        CachedAgent newCached = new CachedAgent(agent, System.currentTimeMillis());
        agentCache.put(configKey, newCached);

        return agent;
    }

    private String generateConfigKey(String baseAgentName, UnifiedChatRequest request) {
        StringBuilder key = new StringBuilder(baseAgentName);

        if (request.getRagMode() != null && !"NONE".equals(request.getRagMode())) {
            key.append("|rag:").append(request.getRagMode());
        }

        if (request.getMcpServers() != null && !request.getMcpServers().isEmpty()) {
            key.append("|mcp:").append(request.getMcpServers());
        }

        if (request.getEnabledTools() != null && !request.getEnabledTools().isEmpty()) {
            key.append("|tools:").append(request.getEnabledTools());
        }

        if (request.getMemoryMode() != null && !"NONE".equals(request.getMemoryMode())) {
            key.append("|mem:").append(request.getMemoryMode());
        }

        if (request.getMaxIters() != null) {
            key.append("|iters:").append(request.getMaxIters());
        }

        return key.toString();
    }

    private boolean isExpired(CachedAgent cached) {
        return System.currentTimeMillis() - cached.createTime > CACHE_TTL;
    }

    public int cleanupExpired() {
        long now = System.currentTimeMillis();
        int beforeSize = agentCache.size();

        agentCache.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().createTime > CACHE_TTL;
            if (expired) {
                log.info("清理过期缓存：key={}", entry.getKey());
            }
            return expired;
        });

        int afterSize = agentCache.size();
        if (beforeSize > afterSize) {
            log.info("缓存清理完成：清理{}个，剩余{}个", beforeSize - afterSize, afterSize);
        }

        return beforeSize - afterSize;
    }

    public void clear() {
        int size = agentCache.size();
        agentCache.clear();
        log.info("缓存已清空，清理{}个 Agent", size);
    }

    public CacheStats getStats() {
        long now = System.currentTimeMillis();
        int expiredCount = 0;

        for (CachedAgent cached : agentCache.values()) {
            if (now - cached.createTime > CACHE_TTL) {
                expiredCount++;
            }
        }

        return new CacheStats(
                agentCache.size(),
                expiredCount,
                MAX_CACHE_SIZE,
                CACHE_TTL);
    }

    private static class CachedAgent {
        final Agent agent;
        final long createTime;
        long lastAccessTime;

        CachedAgent(Agent agent, long createTime) {
            this.agent = agent;
            this.createTime = createTime;
            this.lastAccessTime = createTime;
        }
    }

    public record CacheStats(
            int size,
            int expiredCount,
            int maxSize,
            long ttl) {
    }
}
