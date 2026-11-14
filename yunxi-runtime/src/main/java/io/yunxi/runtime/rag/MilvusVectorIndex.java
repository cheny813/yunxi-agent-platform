package io.yunxi.runtime.rag;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Milvus 的向量索引实现（{@link VectorIndex}）。
 *
 * <p>集合 schema：{@code chunk_id}(VarChar 主键) / {@code user_id}(VarChar) / {@code collection}(VarChar)
 * / {@code embedding}(FloatVector)。向量仅作派生索引，正排内容由 {@link BaseStore} 承载，
 * 因此本索引可随时由正排重建，不构成第二真相源。</p>
 *
 * <p>集合名 = {@code yunxi.rag.milvus.collection-prefix}（默认 {@code yunxi_rag}），多租户/多知识库通过
 * {@code collection} 字段过滤隔离。</p>
 */
@Component("vectorIndex")
@ConditionalOnProperty(name = "yunxi.rag.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(EmbeddingClient.class)
public class MilvusVectorIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorIndex.class);

    private final RagProperties properties;
    private MilvusClientV2 client;
    private String collectionName;
    private volatile boolean healthy = false;

    public MilvusVectorIndex(RagProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        RagProperties.Milvus m = properties.getMilvus();
        collectionName = m.getCollectionPrefix();
        try {
            ConnectConfig connect = ConnectConfig.builder()
                    .uri("http://" + m.getHost() + ":" + m.getPort())
                    .username(m.getUsername())
                    .password(m.getPassword())
                    .dbName(m.getDatabase())
                    .build();
            client = new MilvusClientV2(connect);
            ensureCollection(m.getDimension());
            healthy = true;
            log.info("[RAG] Milvus 向量索引就绪: collection={}", collectionName);
        } catch (Exception e) {
            // 仅向量模式：Milvus 不可用时不可静默降级到关键词，但也不应拖垮整个平台启动。
            // 标记为不健康，检索/写入时抛出明确异常，由 rag_retrieve 工具返回友好提示。
            healthy = false;
            log.error("[RAG] Milvus 向量索引初始化失败（检索将不可用）: {}", e.getMessage());
        }
    }

    /** 向量索引是否健康可用；不可用时上层应给出友好提示而非静默降级。 */
    public boolean isHealthy() {
        return healthy;
    }

    private void ensureCollection(int dimension) {
        boolean exists = client.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        if (!exists) {
            List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("chunk_id").dataType(DataType.VarChar).isPrimaryKey(true).maxLength(64).build());
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("user_id").dataType(DataType.VarChar).maxLength(64).build());
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("collection").dataType(DataType.VarChar).maxLength(128).build());
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("tenant").dataType(DataType.VarChar).maxLength(64).build());
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("embedding").dataType(DataType.FloatVector).dimension(dimension).build());
            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(CreateCollectionReq.CollectionSchema.builder()
                            .fieldSchemaList(fields)
                            .build())
                    .build());
            client.createIndex(io.milvus.v2.service.index.request.CreateIndexReq.builder()
                    .collectionName(collectionName)
                    .indexParams(List.of(IndexParam.builder()
                            .fieldName("embedding")
                            .indexType(IndexParam.IndexType.AUTOINDEX)
                            .metricType(IndexParam.MetricType.COSINE)
                            .build()))
                    .build());
        }
        client.loadCollection(LoadCollectionReq.builder().collectionName(collectionName).build());
    }

    @Override
    public void upsert(String id, String tenant, String collection, String userId, float[] vector) {
        ensureHealthy();
        List<io.milvus.v2.service.vector.request.data.BaseVector> dataVector = new ArrayList<>();
        dataVector.add(new FloatVec(vector));
        com.google.gson.JsonObject row = new com.google.gson.JsonObject();
        row.addProperty("chunk_id", id);
        row.addProperty("user_id", userId);
        row.addProperty("collection", collection);
        row.addProperty("tenant", tenant);
        row.add("embedding", floatArrayToGson(vector));
        client.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(row))
                .build());
    }

    @Override
    public List<String> search(String tenant, String collection, String userId, float[] query, int topK) {
        ensureHealthy();
        List<io.milvus.v2.service.vector.request.data.BaseVector> q = new ArrayList<>();
        q.add(new FloatVec(query));
        String filter = "tenant == \"" + tenant + "\" && collection == \"" + collection
                + "\" && user_id == \"" + userId + "\"";
        SearchResp resp = client.search(SearchReq.builder()
                .collectionName(collectionName)
                .data(q)
                .filter(filter)
                .limit(topK)
                .outputFields(List.of("chunk_id"))
                .build());
        List<String> ids = new ArrayList<>();
        for (List<SearchResp.SearchResult> group : resp.getSearchResults()) {
            for (SearchResp.SearchResult r : group) {
                Object idObj = r.getId();
                if (idObj != null) {
                    ids.add(String.valueOf(idObj));
                }
            }
        }
        return ids;
    }

    @Override
    public void delete(String id) {
        ensureHealthy();
        client.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .ids(List.of(id))
                .build());
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close(20000);
            } catch (Exception e) {
                log.warn("[RAG] Milvus 客户端关闭异常", e);
            }
        }
    }

    private void ensureHealthy() {
        if (!healthy || client == null) {
            throw new IllegalStateException(
                    "RAG 向量索引当前不可用（Milvus 未连接或初始化失败），请检查 yunxi.rag.milvus.* 配置与 Milvus 服务可达性。");
        }
    }

    private com.google.gson.JsonArray floatArrayToGson(float[] v) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (float f : v) {
            arr.add(f);
        }
        return arr;
    }
}
