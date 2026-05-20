package io.yunxi.platform.framework.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具结果缓存服务
 *
 * <p>提供 MCP 工具调用的结果缓存能力，减少重复调用。</p>
 *
 * <h3>功能特性</h3>
 * <ul>
 *   <li>基于 Redis 的分布式缓存</li>
 *   <li>可配置过期时间</li>
 *   <li>自定义缓存 key 生成策略</li>
 *   <li>缓存命中统计</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class ToolCacheService {

    /** MCP 客户端服务 */
    private final McpClientService mcpClientService;
    /** Redis 模板 */
    private final StringRedisTemplate redisTemplate;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /** 缓存 key 生成器映射 */
    private final Map<Class<? extends ToolCacheKeyGenerator>, ToolCacheKeyGenerator> keyGenerators = new ConcurrentHashMap<>();

    /** 缓存统计 */
    private volatile long cacheHits = 0;
    /** 缓存未命中次数 */
    private volatile long cacheMisses = 0;

    /**
     * 构造函数
     *
     * @param mcpClientService MCP 客户端服务
     * @param redisTemplate    Redis 模板
     */
    @Autowired
    public ToolCacheService(McpClientService mcpClientService,
                          StringRedisTemplate redisTemplate) {
        this.mcpClientService = mcpClientService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用工具并使用缓存
     *
     * @param serverName  服务器名称
     * @param toolName     工具名称
     * @param arguments   工具参数
     * @param expireSeconds 缓存过期时间（秒）
     * @return 工具调用结果
     */
    public Object callWithCache(String serverName, String toolName,
                                 Map<String, Object> arguments, int expireSeconds) {
        // 生成缓存 key
        String cacheKey = buildCacheKey(serverName, toolName, arguments);
        String redisKey = "mcp:tool:" + cacheKey;

        // 尝试从缓存获取
        try {
            String cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                cacheHits++;
                log.debug(" MCP 工具缓存命中: {}.{}", serverName, toolName);
                return objectMapper.readValue(cached, Object.class);
            }
        } catch (Exception e) {
            log.warn("读取缓存失败，继续调用工具: {}", e.getMessage());
        }

        cacheMisses++;
        log.debug(" MCP 工具缓存未命中: {}.{}", serverName, toolName);

        // 调用实际工具
        Object result = mcpClientService.callTool(serverName, toolName, arguments);

        // 缓存结果
        if (result != null) {
            try {
                String json = objectMapper.writeValueAsString(result);
                redisTemplate.opsForValue().set(redisKey, json, Duration.ofSeconds(expireSeconds));
                log.debug(" MCP 工具结果已缓存: {}.{}, expire: {}s", serverName, toolName, expireSeconds);
            } catch (Exception e) {
                log.warn("缓存结果失败: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * 简化版：使用默认过期时间
     */
    public Object callWithCache(String serverName, String toolName, Map<String, Object> arguments) {
        return callWithCache(serverName, toolName, arguments, 300);
    }

    /**
     * 构建缓存 key
     */
    private String buildCacheKey(String serverName, String toolName, Map<String, Object> arguments) {
        try {
            // 默认使用 MD5 生成
            DefaultToolCacheKeyGenerator generator = new DefaultToolCacheKeyGenerator();
            return generator.generate(serverName, toolName, arguments);
        } catch (Exception e) {
            return serverName + ":" + toolName + ":" + arguments.hashCode();
        }
    }

    /**
     * 清除指定工具的缓存
     *
     * @param serverName 服务器名称
     * @param toolName  工具名称
     */
    public void invalidate(String serverName, String toolName) {
        redisTemplate.delete("mcp:tool:" + serverName + ":" + toolName + ":*");
        log.info("已清除 MCP 工具缓存: {}.{}", serverName, toolName);
    }

    /**
     * 清除所有 MCP 工具缓存
     */
    public void invalidateAll() {
        var keys = redisTemplate.keys("mcp:tool:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("已清除所有 MCP 工具缓存: {} 条", keys.size());
        }
    }

    /**
     * 获取缓存统计
     */
    public CacheStats getStats() {
        return new CacheStats(cacheHits, cacheMisses);
    }

    /**
     * 缓存统计
     */
    public record CacheStats(long hits, long misses) {
        /**
         * 获取总请求数
         *
         * @return 命中数 + 未命中数
         */
        public long total() {
            return hits + misses;
        }

        /**
         * 获取缓存命中率
         *
         * @return 命中率（0.0 ~ 1.0）
         */
        public double hitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0;
        }
    }
}