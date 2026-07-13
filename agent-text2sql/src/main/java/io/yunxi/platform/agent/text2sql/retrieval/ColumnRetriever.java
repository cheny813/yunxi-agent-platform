package io.yunxi.platform.agent.text2sql.retrieval;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import io.yunxi.platform.agent.text2sql.config.Text2SqlProperties;
import io.yunxi.platform.spi.text2sql.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;

/**
 * 列检索器
 * <p>
 * 基于向量相似度检索相关列，使用 Milvus 存储列的嵌入向量
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class ColumnRetriever {

    private static final Logger log = LoggerFactory.getLogger(ColumnRetriever.class);

    /** Text2SQL 配置属性 */
    private final Text2SqlProperties text2SqlProperties;

    /** 嵌入服务提供者（支持可选注入） */
    private final Supplier<EmbeddingService> embeddingServiceProvider;

    /** Milvus 客户端提供者（支持可选注入） */
    private final Supplier<MilvusClientV2> milvusClientProvider;

    /** JSON 序列化工具 */
    private final Gson gson;

    /**
     * 构造函数，通过依赖注入获取所需依赖
     *
     * @param text2SqlProperties      Text2SQL 配置属性
     * @param embeddingServiceProvider 嵌入服务提供者（可选）
     * @param milvusClientProvider   Milvus 客户端提供者（可选）
     */
    public ColumnRetriever(
            Text2SqlProperties text2SqlProperties,
            Supplier<EmbeddingService> embeddingServiceProvider,
            Supplier<MilvusClientV2> milvusClientProvider) {
        this.text2SqlProperties = text2SqlProperties;
        this.embeddingServiceProvider = embeddingServiceProvider;
        this.milvusClientProvider = milvusClientProvider;
        this.gson = new Gson();
    }

    /**
     * 检索相关列
     *
     * @param query     查询文本
     * @param tableName 表名（可选，用于过滤）
     * @param topK      返回数量
     * @return 相关列列表
     */
    public List<ColumnInfo> retrieveColumns(String query, String tableName, int topK) {
        List<ColumnInfo> results = new ArrayList<>();

        try {
            log.debug("检索列: query={}, table={}, topK={}", query, tableName, topK);

            // 1. 生成查询向量
            List<Float> queryEmbedding = generateEmbedding(query);
            if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                log.warn("生成查询向量失败: query={}", query);
                return results;
            }

            // 2. 在 Milvus 中搜索
            FloatVec queryFloatVec = new FloatVec(queryEmbedding);
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(text2SqlProperties.getRetrieval().getCollectionName())
                    .data(Collections.singletonList(queryFloatVec))
                    .annsField("embedding")
                    .topK(topK)
                    .outputFields(Arrays.asList("column_name", "table_name", "data_type", "description"))
                    .build();

            SearchResp searchResp = milvusClientProvider.get().search(searchReq);

            // 3. 解析结果
            if (searchResp != null && searchResp.getSearchResults() != null
                    && !searchResp.getSearchResults().isEmpty()) {
                for (SearchResp.SearchResult searchResult : searchResp.getSearchResults().get(0)) {
                    ColumnInfo columnInfo = new ColumnInfo();
                    columnInfo.setColumnName(getStringField(searchResult, "column_name"));
                    columnInfo.setTableName(getStringField(searchResult, "table_name"));
                    columnInfo.setDataType(getStringField(searchResult, "data_type"));
                    columnInfo.setDescription(getStringField(searchResult, "description"));
                    columnInfo.setScore(searchResult.getScore());

                    // 如果指定了表名，则按表名过滤
                    if (tableName == null || tableName.equalsIgnoreCase(columnInfo.getTableName())) {
                        results.add(columnInfo);
                    }
                }
            }

            log.info("列检索完成: query={}, found={}, topK={}",
                    query, results.size(), topK);

        } catch (Exception e) {
            log.error("列检索失败: query={}", query, e);
        }

        return results;
    }

    /**
     * 检索相关列（使用默认 topK）
     */
    public List<ColumnInfo> retrieveColumns(String query, String tableName) {
        return retrieveColumns(query, tableName, text2SqlProperties.getRetrieval().getTopK());
    }

    /**
     * 索引数据库列
     *
     * @param databaseId 数据库 ID
     * @param schemas    表 Schema 列表
     */
    public void indexColumns(String databaseId, List<TableSchema> schemas) {
        if (milvusClientProvider.get() == null) {
            log.warn("MilvusClient 未配置，跳过列索引");
            return;
        }

        if (embeddingServiceProvider.get() == null) {
            log.warn("EmbeddingService 未配置，跳过列索引");
            return;
        }

        try {
            log.info("开始索引列: databaseId={}, tables={}", databaseId, schemas.size());

            // 删除旧数据
            deleteColumnsByDatabase(databaseId);

            // 索引新数据
            for (TableSchema tableSchema : schemas) {
                for (ColumnSchema column : tableSchema.getColumns()) {
                    indexColumn(databaseId, tableSchema.getTableName(), column);
                }
            }

            log.info("列索引完成: databaseId={}", databaseId);

        } catch (Exception e) {
            log.error("列索引失败: databaseId={}", databaseId, e);
        }
    }

    /**
     * 索引单个列
     */
    private void indexColumn(String databaseId, String tableName, ColumnSchema column) {
        try {
            // 生成描述文本
            String description = String.format("%s.%s (%s): %s",
                    tableName, column.getColumnName(), column.getBaseType(),
                    column.getDescription() != null ? column.getDescription() : "");

            // 生成向量
            List<Float> embedding = generateEmbedding(description);
            if (embedding == null || embedding.isEmpty()) {
                log.warn("为 {}.{} 生成向量失败", tableName, column.getColumnName());
                return;
            }

            // 创建数据对象
            JsonObject data = new JsonObject();
            data.addProperty("database_id", databaseId);
            data.addProperty("table_name", tableName);
            data.addProperty("column_name", column.getColumnName());
            data.addProperty("data_type", column.getBaseType());
            data.addProperty("description", description);
            data.add("embedding", gson.toJsonTree(embedding));

            // 插入 Milvus
            InsertReq req = InsertReq.builder()
                    .collectionName(text2SqlProperties.getRetrieval().getCollectionName())
                    .data(Collections.singletonList(data))
                    .build();

            milvusClientProvider.get().insert(req);

        } catch (Exception e) {
            log.warn("索引列失败: {}.{}", tableName, column.getColumnName(), e);
        }
    }

    /**
     * 删除数据库的所有列
     */
    private void deleteColumnsByDatabase(String databaseId) {
        try {
            DeleteReq req = DeleteReq.builder()
                    .collectionName(text2SqlProperties.getRetrieval().getCollectionName())
                    .filter("database_id == \"" + databaseId + "\"")
                    .build();

            milvusClientProvider.get().delete(req);
            log.debug("已删除数据库列: databaseId={}", databaseId);

        } catch (Exception e) {
            log.warn("删除数据库列失败: databaseId={}", databaseId, e);
        }
    }

    /**
     * 生成嵌入向量
     */
    private List<Float> generateEmbedding(String text) {
        if (embeddingServiceProvider.get() == null) {
            log.warn("EmbeddingService 未配置");
            return null;
        }

        try {
            float[] embedding = embeddingServiceProvider.get().embed(text);
            if (embedding == null) {
                return null;
            }
            List<Float> result = new ArrayList<>(embedding.length);
            for (float v : embedding) {
                result.add(v);
            }
            return result;
        } catch (Exception e) {
            log.error("生成嵌入向量失败: {}", text, e);
            return null;
        }
    }

    /**
     * 从 SearchResult 获取字段值
     */
    private String getStringField(SearchResp.SearchResult result, String fieldName) {
        try {
            if (result.getEntity() == null) {
                return null;
            }
            Object value = result.getEntity().get(fieldName);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("获取字段值失败: field={}", fieldName, e);
            return null;
        }
    }

    // ==================== 数据模型 ====================

    /**
     * 列信息
     */
    public static class ColumnInfo {
        private String columnName;
        private String tableName;
        private String dataType;
        private String description;
        private double score;

        // getter 和 setter
        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }
    }

    /**
     * 表 Schema
     */
    public static class TableSchema {
        private String tableName;
        private List<ColumnSchema> columns;

        public TableSchema(String tableName) {
            this.tableName = tableName;
            this.columns = new ArrayList<>();
        }

        public TableSchema(String tableName, List<ColumnSchema> columns) {
            this.tableName = tableName;
            this.columns = columns;
        }

        // Getters and Setters
        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public List<ColumnSchema> getColumns() {
            return columns;
        }

        public void setColumns(List<ColumnSchema> columns) {
            this.columns = columns;
        }
    }

    /**
     * 列 Schema（简化版）
     */
    public static class ColumnSchema {
        private String columnName;
        private String baseType;
        private String description;

        public ColumnSchema(String columnName, String baseType) {
            this.columnName = columnName;
            this.baseType = baseType;
        }

        // getter 和 setter
        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getBaseType() {
            return baseType;
        }

        public void setBaseType(String baseType) {
            this.baseType = baseType;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
