package io.yunxi.runtime.rag;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索相关配置（外部化，前缀 {@code yunxi.rag}）。
 *
 * <p>向量检索所需的 Milvus 连接与 Ollama 嵌入服务均由此驱动，默认指向本机
 * docker-compose 预置的 Milvus(19530) 与 Ollama(bge-m3, 11434) 实例。
 */
@Component
@ConfigurationProperties(prefix = "yunxi.rag")
public class RagProperties {

    /** 是否启用 RAG 检索（启用后默认走向量检索）。 */
    private boolean enabled = true;

    /** rag_retrieve 默认返回条数。 */
    private int defaultTopK = 5;

    private final Milvus milvus = new Milvus();

    private final Embedding embedding = new Embedding();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public void setDefaultTopK(int defaultTopK) {
        this.defaultTopK = defaultTopK;
    }

    /**
     * 平台级 RAG 集合白名单（简单版权限回读）。非空时 {@code rag_retrieve} 仅允许访问清单内集合，
     * 拒绝其余（防止读取未授权知识库集合）。缺省空 = 不限制（向后兼容）。
     * 注：本版为平台级全局白名单；按 agent 粒度的授权需先接线 RuntimeContext 的 agentId 注入
     * （当前执行流未在 RuntimeContext 富化 agentId），留后续增强。
     */
    private List<String> allowedCollections = List.of();

    public List<String> getAllowedCollections() {
        return allowedCollections;
    }

    public void setAllowedCollections(List<String> allowedCollections) {
        this.allowedCollections = allowedCollections;
    }

    public Milvus getMilvus() {
        return milvus;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public static class Milvus {
        private String host = "localhost";
        private int port = 19530;
        private String username = "root";
        private String password = "Milvus";
        private String database = "default";
        private String collectionPrefix = "yunxi_rag";
        /** bge-m3 输出维度。 */
        private int dimension = 1024;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getCollectionPrefix() {
            return collectionPrefix;
        }

        public void setCollectionPrefix(String collectionPrefix) {
            this.collectionPrefix = collectionPrefix;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }
    }

    public static class Embedding {
        private String host = "localhost";
        private int port = 11434;
        private String model = "bge-m3";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
