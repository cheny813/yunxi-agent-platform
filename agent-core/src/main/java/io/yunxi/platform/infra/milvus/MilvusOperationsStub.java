package io.yunxi.platform.infra.milvus;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.framework.sync.EmbeddingBatchService;
import io.yunxi.platform.infra.config.MilvusConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.google.gson.JsonObject;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.Collections;
import java.util.List;

/**
 * Milvus 操作空实现（当 milvus.enabled=false 或未配置时激活）
 *
 * <p>
 * 确保总有一个 MilvusOperations Bean 可用，下游类无需 @Autowired(required=false)。
 * 所有操作返回安全默认值，isAvailable() 返回 false。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "false", matchIfMissing = true)
public class MilvusOperationsStub extends MilvusOperations {

    /**
     * 构造方法
     *
     * @param embeddingService      文本向量化服务
     * @param embeddingBatchService 批量向量化服务
     * @param milvusConfig          Milvus 配置
     */
    public MilvusOperationsStub(EmbeddingService embeddingService,
            EmbeddingBatchService embeddingBatchService,
            MilvusConfig milvusConfig) {
        super(null, embeddingService, embeddingBatchService, milvusConfig);
        log.info("MilvusOperationsStub 初始化 (Milvus 未启用，向量功能禁用)");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    @Nullable
    public MilvusClientV2 getRawClient() {
        return null;
    }

    @Override
    public boolean hasCollection(String collectionName) {
        return false;
    }

    @Override
    public boolean createCollection(String collectionName, CreateCollectionReq.CollectionSchema schema) {
        log.debug("Milvus 未启用，跳过创建集合: {}", collectionName);
        return false;
    }

    @Override
    public boolean createCollection(String collectionName, CreateCollectionReq.CollectionSchema schema,
            List<IndexParam> indexParams) {
        log.debug("Milvus 未启用，跳过创建集合: {}", collectionName);
        return false;
    }

    @Override
    public boolean ensureCollection(String collectionName, CreateCollectionReq.CollectionSchema schema) {
        return false;
    }

    @Override
    public boolean ensureCollection(String collectionName, CreateCollectionReq.CollectionSchema schema,
            List<IndexParam> indexParams) {
        return false;
    }

    @Override
    public boolean dropCollection(String collectionName) {
        return false;
    }

    @Override
    public boolean insert(String collectionName, List<JsonObject> data) {
        return false;
    }

    @Override
    public boolean upsert(String collectionName, List<JsonObject> data) {
        return false;
    }

    @Override
    public void upsertBatch(String collectionName, List<JsonObject> dataList, int batchSize) {
        // no-op
    }

    @Override
    public List<SearchResp.SearchResult> search(String collectionName, List<Float> vector,
            int topK, List<String> searchFields,
            @Nullable String filterExpr) {
        return Collections.emptyList();
    }

    @Override
    public List<SearchResp.SearchResult> search(String collectionName, List<Float> vector,
            String annsField, int topK,
            @Nullable String filterExpr,
            List<String> outputFields) {
        return Collections.emptyList();
    }

    @Override
    public boolean delete(String collectionName, String filterExpr) {
        return false;
    }
}
