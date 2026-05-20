package io.yunxi.platform.infra.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 持久化策略配置
 * 
 * <p>
 * 通过配置文件选择数据持久化方式
 * </p>
 * 
 * <p>
 * <b>配置示例</b>：
 * 
 * <pre>
 * persistence:
 *   enabled-strategies:
 *     - database      # 数据库（必需）
 *     - redis         # Redis缓存（推荐）
 *     - vector        # 向量数据库（可选）
 *   cache:
 *     max-size: 1000    # 最大缓存数量
 *     ttl-hours: 24    # 缓存过期时间（小时）
 *   database:
 *     enabled: true    # 是否启用数据库
 *   vector:
 *     enabled: false   # 是否启用向量数据库
 * </pre>
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "persistence")
public class PersistenceConfig {

    /**
     * 启用的策略列表
     * 
     * 可选值: database, redis, vector
     * 默认值: ["database", "redis"]
     */
    private List<String> enabledStrategies = List.of("database", "redis");

    /**
     * 缓存配置
     */
    private CacheConfig cache = new CacheConfig();

    /**
     * 数据库配置
     */
    private DatabaseConfig database = new DatabaseConfig();

    /**
     * 向量数据库配置
     */
    private VectorConfig vector = new VectorConfig();

    /**
     * 缓存配置
     */
    @Data
    public static class CacheConfig {
        /**
         * 最大缓存条目数
         * 超过此数量时，自动淘汰旧数据
         */
        private int maxSize = 10000;

        /**
         * 缓存过期时间（小时）
         */
        private int ttlHours = 24;

        /**
         * 缓存淘汰策略
         * 可选值: LRU, LFU, FIFO
         */
        private String evictionPolicy = "LRU";
    }

    /**
     * 数据库配置
     */
    @Data
    public static class DatabaseConfig {
        /**
         * 是否启用数据库
         */
        private boolean enabled = true;

        /**
         * 批量保存大小
         */
        private int batchSize = 100;
    }

    /**
     * 向量数据库配置
     */
    @Data
    public static class VectorConfig {
        /**
         * 是否启用向量数据库
         */
        private boolean enabled = false;

        /**
         * 向量数据库类型
         * 可选值: milvus, qdrant, weaviate
         */
        private String type = "milvus";

        /**
         * 向量维度
         */
        private int dimension = 1536;

        /**
         * 语义相似度阈值
         */
        private double similarityThreshold = 0.7;
    }
}
