package io.yunxi.platform.agent.text2sql.config;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Text-to-SQL 配置类
 * <p>
 * 统一管理 Text-to-SQL 模块的所有配置项
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
@ConfigurationProperties(prefix = "text2sql")
@Validated
public class Text2SqlProperties {

    /**
     * 是否启用 Text-to-SQL 功能
     */
    private boolean enabled = false;

    /**
     * 候选 SQL 数量（用于 Self-Consistency）
     */
    @Min(1)
    @Max(10)
    private int candidateCount = 3;

    /**
     * 是否启用投票机制
     */
    private boolean useVoting = true;

    /**
     * 是否启用 SQL 对齐
     */
    private boolean useAlignment = true;

    /**
     * 检索配置
     */
    @Valid
    @NotNull
    private RetrievalProperties retrieval = new RetrievalProperties();

    /**
     * Few-shot 配置
     */
    @Valid
    @NotNull
    private FewShotProperties fewshot = new FewShotProperties();

    /**
     * 投票配置
     */
    @Valid
    @NotNull
    private VotingProperties voting = new VotingProperties();

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public boolean isUseVoting() {
        return useVoting;
    }

    public void setUseVoting(boolean useVoting) {
        this.useVoting = useVoting;
    }

    public boolean isUseAlignment() {
        return useAlignment;
    }

    public void setUseAlignment(boolean useAlignment) {
        this.useAlignment = useAlignment;
    }

    public RetrievalProperties getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(RetrievalProperties retrieval) {
        this.retrieval = retrieval;
    }

    public FewShotProperties getFewshot() {
        return fewshot;
    }

    public void setFewshot(FewShotProperties fewshot) {
        this.fewshot = fewshot;
    }

    public VotingProperties getVoting() {
        return voting;
    }

    public void setVoting(VotingProperties voting) {
        this.voting = voting;
    }

    /**
     * 检索配置
     */
    public static class RetrievalProperties {
        /**
         * Milvus 集合名称
         */
        private String collectionName = "column_embeddings";

        /**
         * 向量维度
         */
        @Min(1)
        @Max(4096)
        private int dimension = 1536;

        /**
         * Top-K 数量
         */
        @Min(1)
        @Max(100)
        private int topK = 10;

        /**
         * Milvus 主机地址
         */
        private String milvusHost = "localhost";

        /**
         * Milvus 端口
         */
        @Min(1)
        @Max(65535)
        private int milvusPort = 19530;

        // Getters and Setters

        public String getCollectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public String getMilvusHost() {
            return milvusHost;
        }

        public void setMilvusHost(String milvusHost) {
            this.milvusHost = milvusHost;
        }

        public int getMilvusPort() {
            return milvusPort;
        }

        public void setMilvusPort(int milvusPort) {
            this.milvusPort = milvusPort;
        }
    }

    /**
     * Few-shot 配置
     */
    public static class FewShotProperties {
        /**
         * 最大示例数量
         */
        @Min(1)
        @Max(1000)
        private int maxExamples = 50;

        /**
         * 相似度阈值
         */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double similarityThreshold = 0.7;

        // Getters and Setters

        public int getMaxExamples() {
            return maxExamples;
        }

        public void setMaxExamples(int maxExamples) {
            this.maxExamples = maxExamples;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }
    }

    /**
     * 投票配置
     */
    public static class VotingProperties {
        /**
         * 是否启用投票
         */
        private boolean enabled = true;

        /**
         * 超时时间（秒）
         */
        @Min(1)
        @Max(300)
        private int timeout = 30;

        // Getters and Setters

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTimeout() {
            return timeout;
        }

        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }
    }
}
