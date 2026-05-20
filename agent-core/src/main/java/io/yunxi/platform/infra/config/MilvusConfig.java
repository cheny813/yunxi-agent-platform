package io.yunxi.platform.infra.config;

import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量数据库配置属性
 *
 * <p>
 * 配置示例：
 * 
 * <pre>
 * milvus:
 *   enabled: true
 *   host: localhost
 *   port: 19530
 *   database: default
 *   collections:
 *     user-memory: user_memory        # 用户级记忆向量集合
 *     recipe-history: recipe_history  # 食谱历史向量集合
 *   embedding:
 *     dimension: 1536                 # 向量维度
 *     model: text-embedding-v3        # 嵌入模型
 * </pre>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusConfig {

    /**
     * 是否启用 Milvus
     */
    private boolean enabled = false;

    /**
     * Milvus 服务地址（必填，无默认值）
     */
    private String host;

    /**
     * Milvus 服务端口
     */
    private int port = 19530;

    /**
     * 数据库名称
     */
    private String database = "default";

    /**
     * 用户名（可选，Milvus 认证启用时需要）
     */
    private String username;

    /**
     * 密码（可选，Milvus 认证启用时需要）
     */
    private String password;

    /**
     * Token（可选，替代用户名密码的认证方式）
     * 如果设置了 token，将优先使用 token 认证
     */
    private String token;

    /**
     * 连接超时时间（秒）
     */
    private int connectTimeout = 10;

    /**
     * 查询超时时间（秒）
     */
    private int queryTimeout = 30;

    /**
     * 集合配置
     */
    private CollectionsConfig collections = new CollectionsConfig();

    /**
     * 嵌入向量配置
     */
    private EmbeddingConfig embedding = new EmbeddingConfig();

    /**
     * 集合配置类
     */
    @Data
    public static class CollectionsConfig {
        /**
         * 用户级记忆向量集合名称
         */
        private String userMemory = "user_memory";

        /**
         * 业务历史向量集合名称（由业务层配置）
         */
        private String businessHistory = "business_history";

        /**
         * 会话记忆向量集合名称
         */
        private String conversationMemory = "conversation_memory";
    }

    /**
     * 嵌入向量配置类
     */
    @Data
    public static class EmbeddingConfig {
        /**
         * 向量维度
         */
        private int dimension = ConfigDefaults.DEFAULT_EMBEDDING_DIMENSION;

        /**
         * 嵌入模型（用于文本向量化）
         */
        private String model = ConfigDefaults.DEFAULT_EMBEDDING_MODEL;

        /**
         * 索引类型
         */
        private String indexType = ConfigDefaults.DEFAULT_INDEX_TYPE;

        /**
         * 相似度度量类型
         */
        private String metricType = ConfigDefaults.DEFAULT_METRIC_TYPE;

        /**
         * IVF 参数：聚类数量
         */
        private int nlist = ConfigDefaults.DEFAULT_NLIST;

        /**
         * 动态更新向量维度
         * 当从 Embedding API 检测到实际维度与配置不同时调用
         *
         * @param actualDimension 实际检测到的维度
         */
        public void updateDimension(int actualDimension) {
            if (this.dimension != actualDimension) {
                this.dimension = actualDimension;
            }
        }
    }
}
