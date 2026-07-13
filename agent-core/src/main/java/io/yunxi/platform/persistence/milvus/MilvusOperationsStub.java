package io.yunxi.platform.persistence.milvus;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.yunxi.platform.embedding.EmbeddingService;
import io.yunxi.platform.sync.EmbeddingBatchService;
import io.yunxi.platform.config.MilvusConfig;
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
 * <p>确保总有一个 MilvusOperations Bean 可用，下游类无需 @Autowired(required=false)。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "false", matchIfMissing = true)
public class MilvusOperationsStub extends MilvusOperations {

    /**
     * 构造 Milvus 空操作桩（Milvus 未启用时使用）。
     *
     * @param embeddingService      嵌入服务
     * @param embeddingBatchService 批量嵌入服务
     * @param milvusConfig          Milvus 配置
     */
    public MilvusOperationsStub(EmbeddingService embeddingService,
            EmbeddingBatchService embeddingBatchService,
            MilvusConfig milvusConfig) {
        super(null, embeddingService, embeddingBatchService, milvusConfig);
        log.info("MilvusOperationsStub 初始化完成（Milvus 未启用，向量功能禁用）");
    }

    /** 空实现：Milvus 不可用，恒返回 false。 */
    @Override public boolean isAvailable() { return false; }
    /** 空实现：返回 null 客户端。 */
    @Override @Nullable public MilvusClientV2 getRawClient() { return null; }
    /** 空实现：集合永不“存在”。 */
    @Override public boolean hasCollection(String collectionName) { return false; }
    /** 空实现：创建集合恒返回 false。 */
    @Override public boolean createCollection(String collectionName, CreateCollectionReq.CollectionSchema schema) { return false; }
    /** 空实现：创建集合恒返回 false。 */
    @Override public boolean createCollection(String collectionName, CreateCollectionReq.CollectionSchema schema, List<IndexParam> indexParams) { return false; }
    /** 空实现：确保集合存在恒返回 false。 */
    @Override public boolean ensureCollection(String collectionName, CreateCollectionReq.CollectionSchema schema) { return false; }
    /** 空实现：确保集合存在恒返回 false。 */
    @Override public boolean ensureCollection(String collectionName, CreateCollectionReq.CollectionSchema schema, List<IndexParam> indexParams) { return false; }
    /** 空实现：删除集合恒返回 false。 */
    @Override public boolean dropCollection(String collectionName) { return false; }
    /** 空实现：插入数据恒返回 false。 */
    @Override public boolean insert(String collectionName, List<JsonObject> data) { return false; }
    /** 空实现：upsert 数据恒返回 false。 */
    @Override public boolean upsert(String collectionName, List<JsonObject> data) { return false; }
    /** 空实现：批量 upsert 无操作。 */
    @Override public void upsertBatch(String collectionName, List<JsonObject> dataList, int batchSize) {}
    /** 空实现：搜索返回空列表。 */
    @Override public List<SearchResp.SearchResult> search(String collectionName, List<Float> vector, int topK, List<String> searchFields, @Nullable String filterExpr) { return Collections.emptyList(); }
    /** 空实现：搜索返回空列表。 */
    @Override public List<SearchResp.SearchResult> search(String collectionName, List<Float> vector, String annsField, int topK, @Nullable String filterExpr, List<String> outputFields) { return Collections.emptyList(); }
    /** 空实现：删除数据恒返回 false。 */
    @Override public boolean delete(String collectionName, String filterExpr) { return false; }
}
