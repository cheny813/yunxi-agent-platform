package io.yunxi.platform.spi.vector;

import java.util.List;
import java.util.Map;

/**
 * 向量持久化服务 SPI 接口
 *
 * <p>提供向量存储和语义检索能力，支持：
 * <ul>
 *   <li>用户记忆存储与检索</li>
 *   <li>语义相似度搜索</li>
 *   <li>元数据过滤</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface VectorPersistenceProvider {

    /**
     * 保存用户记忆
     *
     * @param userId   用户ID
     * @param content  记忆内容
     * @param metadata 元数据
     * @return 是否成功
     */
    boolean saveUserMemory(String userId, String content, Map<String, Object> metadata);

    /**
     * 语义搜索相似记忆
     *
     * @param query  查询文本
     * @param userId 用户ID（可选，用于过滤）
     * @param topK   返回数量
     * @return 搜索结果列表
     */
    List<SearchResult> searchSimilarMemory(String query, String userId, int topK);

    /**
     * 搜索结果
     */
    class SearchResult {
        private String id;
        private String content;
        private Map<String, Object> metadata;
        private double score;
        private Long createdAt;

        public SearchResult() {}

        public SearchResult(String id, String content, Map<String, Object> metadata, double score, Long createdAt) {
            this.id = id;
            this.content = content;
            this.metadata = metadata;
            this.score = score;
            this.createdAt = createdAt;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public Long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Long createdAt) {
            this.createdAt = createdAt;
        }
    }
}
