package io.yunxi.platform.framework.agent;

import io.agentscope.core.ReActAgent;
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
 * <p>
 * <b>策略</b>:
 * <ul>
 * <li>为不同的配置组合创建独立的 Agent 实例</li>
 * <li>缓存 Agent 实例以提高性能</li>
 * <li>提供缓存清理机制</li>
 * <li>支持动态配置</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Service
public class AgentConfigDtoCache {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigDtoCache.class);

    /**
     * 配置缓存：key = 配置签名，value = Agent 实例
     */
    private final Map<String, CachedAgent> agentCache = new ConcurrentHashMap<>();

    /**
     * 最大缓存大小
     */
    private static final int MAX_CACHE_SIZE = 100;

    /**
     * 缓存 TTL（毫秒）
     */
    private static final long CACHE_TTL = 30 * 60 * 1000; // 30 分钟

    /**
     * 获取或创建配置的 Agent
     *
     * @param baseAgentName 基础 Agent 名称
     * @param request       请求配置
     * @param factory       Agent 工厂
     * @return Agent 实例
     */
    public ReActAgent getOrCreateAgent(String baseAgentName,
            UnifiedChatRequest request,
            AdvancedAgentFactory factory) {
        // 生成配置签名
        String configKey = generateConfigKey(baseAgentName, request);

        // 检查缓存
        CachedAgent cached = agentCache.get(configKey);
        if (cached != null && !isExpired(cached)) {
            log.info("从缓存获取 Agent: key={}", configKey);
            cached.lastAccessTime = System.currentTimeMillis();
            return cached.agent;
        }

        // 创建新 Agent
        log.info("创建新 Agent: key={}", configKey);
        ReActAgent agent = factory.createTempAgent(baseAgentName, request);

        if (agent == null) {
            log.warn("Agent 创建失败，使用基础 Agent: baseAgent={}", baseAgentName);
            return null;
        }

        // 缓存 Agent
        if (agentCache.size() >= MAX_CACHE_SIZE) {
            cleanupExpired();
        }

        CachedAgent newCached = new CachedAgent(agent, System.currentTimeMillis());
        agentCache.put(configKey, newCached);

        return agent;
    }

    /**
     * 生成配置签名
     *
     * @param baseAgentName 基础 Agent 名称
     * @param request       请求配置
     * @return 配置签名字符串
     */
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

    /**
     * 检查缓存是否过期
     *
     * @param cached 缓存的 Agent 实例
     * @return 如果过期返回 true
     */
    private boolean isExpired(CachedAgent cached) {
        return System.currentTimeMillis() - cached.createTime > CACHE_TTL;
    }

    /**
     * 清理过期缓存
     *
     * @return 清理的缓存数量
     */
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

    /**
     * 清空所有缓存
     */
    public void clear() {
        int size = agentCache.size();
        agentCache.clear();
        log.info("缓存已清空，清理{}个 Agent", size);
    }

    /**
     * 获取缓存统计信息
     *
     * @return 缓存统计信息
     */
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

    /**
     * 缓存的 Agent 实例
     */
    private static class CachedAgent {
        /** Agent 实例 */
        final ReActAgent agent;
        /** 创建时间 */
        final long createTime;
        /** 最后访问时间 */
        long lastAccessTime;

        /**
         * 构造缓存 Agent 实例
         *
         * @param agent      Agent 实例
         * @param createTime 创建时间
         */
        CachedAgent(ReActAgent agent, long createTime) {
            this.agent = agent;
            this.createTime = createTime;
            this.lastAccessTime = createTime;
        }
    }

    /**
     * 缓存统计信息
     */
    public record CacheStats(
            int size,
            int expiredCount,
            int maxSize,
            long ttl) {
    }
}
