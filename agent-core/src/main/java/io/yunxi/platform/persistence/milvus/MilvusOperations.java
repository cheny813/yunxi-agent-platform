package io.yunxi.platform.persistence.milvus;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.google.gson.JsonObject;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import io.yunxi.platform.embedding.EmbeddingService;
import io.yunxi.platform.sync.EmbeddingBatchService;
import io.yunxi.platform.config.MilvusConfig;

/**
 * Milvus 操作门面
 *
 * <p>
 * 统一封装所有 Milvus 操作，内置 null 安全检查和错误处理，
 * 使下游业务层无需关心 MilvusClientV2 可能为 null 的问题。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Service
public class MilvusOperations {

    private static final Logger log = LoggerFactory.getLogger(MilvusOperations.class);

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final EmbeddingBatchService embeddingBatchService;
    private final MilvusConfig milvusConfig;
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();

    public MilvusOperations(
            ObjectProvider<MilvusClientV2> milvusClientProvider,
            EmbeddingService embeddingService,
            EmbeddingBatchService embeddingBatchService,
            MilvusConfig milvusConfig) {
        this.milvusClient = milvusClientProvider.getIfAvailable();
        this.embeddingService = embeddingService;
        this.embeddingBatchService = embeddingBatchService;
        this.milvusConfig = milvusConfig;
        if (milvusClient != null) {
            log.info("MilvusOperations 初始化完成（Milvus 可用）");
        } else {
            log.warn("MilvusOperations 初始化完成（Milvus 不可用，向量功能降级）");
        }
    }

    public boolean isAvailable() { return milvusClient != null; }

    @Nullable
    public MilvusClientV2 getRawClient() { return milvusClient; }

    public List<String> listCollections() {
        if (!isAvailable()) return Collections.emptyList();
        try {
            io.milvus.v2.service.collection.response.ListCollectionsResp resp = milvusClient.listCollections();
            return resp.getCollectionNames();
        } catch (Exception e) {
            log.error("列出集合失败", e);
            return Collections.emptyList();
        }
    }

    public boolean hasCollection(String collectionName) {
        if (!isAvailable()) return false;
        try {
            HasCollectionReq req = HasCollectionReq.builder().collectionName(collectionName).build();
            return milvusClient.hasCollection(req);
        } catch (Exception e) {
            log.error("检查集合存在失败: {}", collectionName, e);
            return false;
        }
    }

    public long getCollectionStatistics(String collectionName) {
        if (!isAvailable()) return -1;
        try {
            GetCollectionStatsReq req = GetCollectionStatsReq.builder().collectionName(collectionName).build();
            GetCollectionStatsResp resp = milvusClient.getCollectionStats(req);
            return resp.getNumOfEntities();
        } catch (Exception e) {
            log.error("获取集合统计信息失败: {}", collectionName, e);
            return -1;
        }
    }

    public boolean createCollection(String collectionName, CreateCollectionReq.CollectionSchema schema) {
        if (!isAvailable()) return false;
        try {
            CreateCollectionReq req = CreateCollectionReq.builder()
                    .collectionName(collectionName).collectionSchema(schema).build();
            milvusClient.createCollection(req);
            initializedCollections.add(collectionName);
            return true;
        } catch (Exception e) {
            log.error("创建集合失败: {}", collectionName, e);
            return false;
        }
    }

    public boolean createCollection(String collectionName, String description,
            CreateCollectionReq.CollectionSchema schema, List<IndexParam> indexParams) {
        if (!isAvailable()) return false;
        try {
            CreateCollectionReq req = CreateCollectionReq.builder()
                    .collectionName(collectionName).description(description)
                    .collectionSchema(schema).indexParams(indexParams).build();
            milvusClient.createCollection(req);
            initializedCollections.add(collectionName);
            return true;
        } catch (Exception e) {
            log.error("创建集合失败（含索引）: {}", collectionName, e);
            return false;
        }
    }

    public boolean createCollection(String collectionName, CreateCollectionReq.CollectionSchema schema,
            List<IndexParam> indexParams) {
        if (!isAvailable()) return false;
        try {
            CreateCollectionReq req = CreateCollectionReq.builder()
                    .collectionName(collectionName).collectionSchema(schema).indexParams(indexParams).build();
            milvusClient.createCollection(req);
            initializedCollections.add(collectionName);
            return true;
        } catch (Exception e) {
            log.error("创建集合失败（含索引）: {}", collectionName, e);
            return false;
        }
    }

    public boolean ensureCollection(String collectionName, CreateCollectionReq.CollectionSchema schema) {
        if (!isAvailable()) return false;
        if (initializedCollections.contains(collectionName)) return false;
        try {
            if (!hasCollection(collectionName)) { createCollection(collectionName, schema); return true; }
            initializedCollections.add(collectionName);
            return false;
        } catch (Exception e) {
            log.error("确保集合存在失败: {}", collectionName, e);
            return false;
        }
    }

    public boolean ensureCollection(String collectionName, CreateCollectionReq.CollectionSchema schema,
            List<IndexParam> indexParams) {
        if (!isAvailable()) return false;
        if (initializedCollections.contains(collectionName)) return false;
        try {
            if (!hasCollection(collectionName)) { createCollection(collectionName, schema, indexParams); return true; }
            initializedCollections.add(collectionName);
            return false;
        } catch (Exception e) {
            log.error("确保集合存在失败: {}", collectionName, e);
            return false;
        }
    }

    public boolean dropCollection(String collectionName) {
        if (!isAvailable()) return false;
        try {
            milvusClient.dropCollection(io.milvus.v2.service.collection.request.DropCollectionReq.builder()
                    .collectionName(collectionName).build());
            initializedCollections.remove(collectionName);
            return true;
        } catch (Exception e) {
            log.error("删除集合失败: {}", collectionName, e);
            return false;
        }
    }

    public void markCollectionInitialized(String collectionName) { initializedCollections.add(collectionName); }
    public void clearCollectionCache(String collectionName) { initializedCollections.remove(collectionName); }

    public boolean insert(String collectionName, List<JsonObject> data) {
        if (!isAvailable()) return false;
        try {
            milvusClient.insert(InsertReq.builder().collectionName(collectionName).data(data).build());
            return true;
        } catch (Exception e) {
            log.error("插入数据失败: collection={}, count={}", collectionName, data.size(), e);
            return false;
        }
    }

    public boolean upsert(String collectionName, List<JsonObject> data) {
        if (!isAvailable()) return false;
        try {
            milvusClient.upsert(UpsertReq.builder().collectionName(collectionName).data(data).build());
            return true;
        } catch (Exception e) {
            log.error("Upsert 数据失败: collection={}, count={}", collectionName, data.size(), e);
            return false;
        }
    }

    public void upsertBatch(String collectionName, List<JsonObject> dataList, int batchSize) {
        if (!isAvailable() || dataList == null || dataList.isEmpty()) return;
        int totalSize = dataList.size();
        int offset = 0;
        while (offset < totalSize) {
            int end = Math.min(offset + batchSize, totalSize);
            List<JsonObject> batch = dataList.subList(offset, end);
            try {
                milvusClient.upsert(UpsertReq.builder().collectionName(collectionName).data(batch).build());
            } catch (Exception e) {
                log.warn("批量 upsert 失败: collection={}, offset={}", collectionName, offset, e);
            }
            offset = end;
        }
    }

    public List<SearchResp.SearchResult> search(String collectionName, List<Float> vector,
            int topK, List<String> searchFields, @Nullable String filterExpr) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            SearchReq.SearchReqBuilder b = SearchReq.builder()
                    .collectionName(collectionName).data(Collections.singletonList(new FloatVec(vector)))
                    .topK(topK).outputFields(searchFields);
            if (filterExpr != null && !filterExpr.isEmpty()) b.filter(filterExpr);
            SearchResp resp = milvusClient.search(b.build());
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            return results != null && !results.isEmpty() ? results.get(0) : Collections.emptyList();
        } catch (Exception e) {
            log.error("向量搜索失败: collection={}", collectionName, e);
            return Collections.emptyList();
        }
    }

    public List<SearchResp.SearchResult> search(String collectionName, List<Float> vector,
            String annsField, int topK, @Nullable String filterExpr, List<String> outputFields) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            SearchReq.SearchReqBuilder b = SearchReq.builder()
                    .collectionName(collectionName).data(Collections.singletonList(new FloatVec(vector)))
                    .annsField(annsField).topK(topK).outputFields(outputFields);
            if (filterExpr != null && !filterExpr.isEmpty()) b.filter(filterExpr);
            SearchResp resp = milvusClient.search(b.build());
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            return results != null && !results.isEmpty() ? results.get(0) : Collections.emptyList();
        } catch (Exception e) {
            log.error("向量搜索失败: collection={}", collectionName, e);
            return Collections.emptyList();
        }
    }

    public boolean delete(String collectionName, String filterExpr) {
        if (!isAvailable()) return false;
        try {
            milvusClient.delete(DeleteReq.builder().collectionName(collectionName).filter(filterExpr).build());
            return true;
        } catch (Exception e) {
            log.error("删除数据失败: collection={}", collectionName, e);
            return false;
        }
    }

    public int getEmbeddingDimension() { return embeddingService.getDimension(); }
    public List<Float> embed(String text) { try { return embeddingService.embed(text); } catch (Exception e) { log.error("Text embedding failed", e); return Collections.emptyList(); } }
    public List<List<Float>> embedBatch(List<String> texts) { return embeddingBatchService.embedBatchWithRetry(texts); }
    public MilvusConfig getConfig() { return milvusConfig; }
}
