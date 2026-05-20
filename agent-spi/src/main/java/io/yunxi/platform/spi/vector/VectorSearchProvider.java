package io.yunxi.platform.spi.vector;

import java.util.List;
import java.util.Map;

/**
 * 向量搜索提供者接口（SPI）
 *
 * <p>框架层定义的抽象接口，由业务层实现。遵循依赖倒置原则。</p>
 *
 * <p>用于解耦框架层与业务层的直接依赖，框架层通过此接口进行向量搜索，
 * 具体实现由业务层提供。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface VectorSearchProvider {

    /**
     * 搜索向量数据
     *
     * @param contextId 上下文ID（如学校ID）
     * @param query     查询文本
     * @param topK      返回数量
     * @return 搜索结果列表
     */
    List<VectorData> search(Long contextId, String query, int topK);

    /**
     * 向量数据结构
     */
    class VectorData {
        private String id;
        private String name;
        private String type;
        private Map<String, Object> metadata;
        private String content;

        public VectorData(String id, String name, String type, Map<String, Object> metadata, String content) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.metadata = metadata;
            this.content = content;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public String getContent() {
            return content;
        }
    }
}
