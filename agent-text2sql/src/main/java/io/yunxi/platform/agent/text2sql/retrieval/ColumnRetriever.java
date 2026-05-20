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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 列检索器
 * <p>
 * 基于向量相似度检索相关列，使用 Milvus 存储列的嵌入向量
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
public class ColumnRetriever {

    private static final Logger log = LoggerFactory.getLogger(ColumnRetriever.class);

    @Autowired
    private Text2SqlProperties text2SqlProperties;

    @Autowired(required = false)
    private EmbeddingService embeddingService;

    @Autowired(required = false)
    private MilvusClientV2 milvusClient;

    private final Gson gson = new Gson();

    // Setter methods for testing
    public void setText2SqlProperties(Text2SqlProperties text2SqlProperties) {
        this.text2SqlProperties = text2SqlProperties;
    }

    public void setMilvusClient(MilvusClientV2 milvusClient) {
        this.milvusClient = milvusClient;
    }

    public void setEmbeddingService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
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
            log.debug("Retrieving columns: query={}, table={}, topK={}", query, tableName, topK);

            // 1. Generate query embedding
            List<Float> queryEmbedding = generateEmbedding(query);
            if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                log.warn("Failed to generate query embedding: query={}", query);
                return results;
            }

            // 2. Search in Milvus
            FloatVec queryFloatVec = new FloatVec(queryEmbedding);
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(text2SqlProperties.getRetrieval().getCollectionName())
                    .data(Collections.singletonList(queryFloatVec))
                    .annsField("embedding")
                    .topK(topK)
                    .outputFields(Arrays.asList("column_name", "table_name", "data_type", "description"))
                    .build();

            SearchResp searchResp = milvusClient.search(searchReq);

            // 3. Parse results
            if (searchResp != null && searchResp.getSearchResults() != null
                    && !searchResp.getSearchResults().isEmpty()) {
                for (SearchResp.SearchResult searchResult : searchResp.getSearchResults().get(0)) {
                    ColumnInfo columnInfo = new ColumnInfo();
                    columnInfo.setColumnName(getStringField(searchResult, "column_name"));
                    columnInfo.setTableName(getStringField(searchResult, "table_name"));
                    columnInfo.setDataType(getStringField(searchResult, "data_type"));
                    columnInfo.setDescription(getStringField(searchResult, "description"));
                    columnInfo.setScore(searchResult.getScore());

                    // Filter by table name if specified
                    if (tableName == null || tableName.equalsIgnoreCase(columnInfo.getTableName())) {
                        results.add(columnInfo);
                    }
                }
            }

            log.info("Column retrieval completed: query={}, found={}, topK={}",
                    query, results.size(), topK);

        } catch (Exception e) {
            log.error("Column retrieval failed: query={}", query, e);
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
        if (milvusClient == null) {
            log.warn("MilvusClient not configured, skipping column indexing");
            return;
        }

        if (embeddingService == null) {
            log.warn("EmbeddingService not configured, skipping column indexing");
            return;
        }

        try {
            log.info("Starting column indexing: databaseId={}, tables={}", databaseId, schemas.size());

            // Delete old data
            deleteColumnsByDatabase(databaseId);

            // Index new data
            for (TableSchema tableSchema : schemas) {
                for (ColumnSchema column : tableSchema.getColumns()) {
                    indexColumn(databaseId, tableSchema.getTableName(), column);
                }
            }

            log.info("Column indexing completed: databaseId={}", databaseId);

        } catch (Exception e) {
            log.error("Column indexing failed: databaseId={}", databaseId, e);
        }
    }

    /**
     * 索引单个列
     */
    private void indexColumn(String databaseId, String tableName, ColumnSchema column) {
        try {
            // Generate description text
            String description = String.format("%s.%s (%s): %s",
                    tableName, column.getColumnName(), column.getBaseType(),
                    column.getDescription() != null ? column.getDescription() : "");

            // Generate embedding
            List<Float> embedding = generateEmbedding(description);
            if (embedding == null || embedding.isEmpty()) {
                log.warn("Failed to generate embedding for: {}.{}", tableName, column.getColumnName());
                return;
            }

            // Create data object
            JsonObject data = new JsonObject();
            data.addProperty("database_id", databaseId);
            data.addProperty("table_name", tableName);
            data.addProperty("column_name", column.getColumnName());
            data.addProperty("data_type", column.getBaseType());
            data.addProperty("description", description);
            data.add("embedding", gson.toJsonTree(embedding));

            // Insert into Milvus
            InsertReq req = InsertReq.builder()
                    .collectionName(text2SqlProperties.getRetrieval().getCollectionName())
                    .data(Collections.singletonList(data))
                    .build();

            milvusClient.insert(req);

        } catch (Exception e) {
            log.warn("Failed to index column: {}.{}", tableName, column.getColumnName(), e);
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

            milvusClient.delete(req);
            log.debug("Deleted database columns: databaseId={}", databaseId);

        } catch (Exception e) {
            log.warn("Failed to delete database columns: databaseId={}", databaseId, e);
        }
    }

    /**
     * 生成嵌入向量
     */
    private List<Float> generateEmbedding(String text) {
        if (embeddingService == null) {
            log.warn("EmbeddingService not configured");
            return null;
        }

        try {
            float[] embedding = embeddingService.embed(text);
            if (embedding == null) {
                return null;
            }
            List<Float> result = new ArrayList<>(embedding.length);
            for (float v : embedding) {
                result.add(v);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to generate embedding: {}", text, e);
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
            log.warn("Failed to get field value: field={}", fieldName, e);
            return null;
        }
    }

    // ==================== Data Models ====================

    /**
     * 列信息
     */
    public static class ColumnInfo {
        private String columnName;
        private String tableName;
        private String dataType;
        private String description;
        private double score;

        // Getters and Setters
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

        // Getters and Setters
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
