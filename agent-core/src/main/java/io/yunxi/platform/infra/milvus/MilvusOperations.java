package io.yunxi.platform.infra.milvus;

import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.framework.sync.EmbeddingBatchService;
import io.yunxi.platform.infra.config.MilvusConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus 操作门面
 *
 * <p>
 * 统一封装所有 Milvus 操作，内聚 null 安全检查和错误处理，
 * 使下游业务类无需关心 MilvusClientV2 可能为 null 的问题。
 * </p>
 *
 * <p>
 * <b>设计原则</b>：
 * <ul>
 * <li>唯一注入点：MilvusClientV2 仅在此处注入（required=false），其他类通过 MilvusOperations
 * 访问</li>
 * <li>null 安全：所有方法内部检查 isAvailable()，不可用时返回安全默认值</li>
 * <li>集合缓存：initializedCollections 避免重复 hasCollection RPC 调用</li>
 * <li>错误处理：操作失败 log.error + 返回空结果/false，不抛异常</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Service
public class MilvusOperations {

    private static final Logger log = LoggerFactory.getLogger(MilvusOperations.class);

    /** Milvus 客户端（可能为 null，表示 Milvus 不可用） */
    private final MilvusClientV2 milvusClient;
    /** 文本向量化服务 */
    private final EmbeddingService embeddingService;
    /** 批量文本向量化服务 */
    private final EmbeddingBatchService embeddingBatchService;
    /** Milvus 配置 */
    private final MilvusConfig milvusConfig;
    /** 已初始化的集合名称缓存 */
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();

    /**
     * 构造方法
     *
     * @param milvusClient          Milvus 客户端（可选）
     * @param embeddingService      文本向量化服务
     * @param embeddingBatchService 批量向量化服务
     * @param milvusConfig          Milvus 配置
     */
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
            log.info("MilvusOperations 初始化完成 (Milvus 可用)");
        } else {
            log.warn("MilvusOperations 初始化完成 (Milvus 不可用，向量功能降级)");
        }
    }

    // ==================== 可用性检查 ====================

    /**
     * Milvus 服务是否可用
     *
     * @return true 表示可用
     */
    public boolean isAvailable() {
        return milvusClient != null;
    }

    /**
     * 获取原始 MilvusClientV2（供高级操作使用，可能为 null）
     *
     * @return Milvus 客户端实例，不可用时返回 null
     */
    @Nullable
    public MilvusClientV2 getRawClient() {
        return milvusClient;
    }

    // ==================== 集合管理 ====================

    /**
     * 列出所有集合名称
     *
     * @return 集合名称列表，Milvus 不可用时返回空列表
     */
    public List<String> listCollections() {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        try {
            io.milvus.v2.service.collection.response.ListCollectionsResp resp = milvusClient.listCollections();
            return resp.getCollectionNames();
        } catch (Exception e) {
            log.error("列出集合失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 检查集合是否存在
     *
     * @param collectionName 集合名称
     * @return true-存在，false-不存在或 Milvus 不可用
     */
    public boolean hasCollection(String collectionName) {
        if (!isAvailable()) {
            return false;
        }
        try {
            HasCollectionReq req = HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build();
            return milvusClient.hasCollection(req);
        } catch (Exception e) {
            log.error("检查集合存在失败: {}", collectionName, e);
            return false;
        }
    }

    /**
     * 创建集合
     *
     * @param collectionName 集合名称
     * @param schema         集合 schema
     * @return true-创建成功，false-创建失败或 Milvus 不可用
     */
    public boolean createCollection(String collectionName, CreateCollectionReq.CollectionSchema schema) {
        if (!isAvailable()) {
            log.warn("Milvus 不可用，跳过创建集合: {}", collectionName);
            return false;
        }
        try {
            CreateCollectionReq req = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(schema)
                    .build();
            milvusClient.createCollection(req);
            initializedCollections.add(collectionName);
            log.info("集合创建成功: {}", collectionName);
            return true;
        } catch (Exception e) {
            log.error("创建集合失败: {}", collectionName, e);
            return false;
        }
    }

    /**
     * 创建集合（带索引参数）
     *
     * @param collectionName 集合名称
     * @param schema         集合 schema
     * @param indexParams    索引参数
     * @return true-创建成功
     */
    public boolean createCollection(String collectionName, CreateCollectionReq.CollectionSchema schema,
            List<IndexParam> indexParams) {
        if (!isAvailable()) {
            log.warn("Milvus 不可用，跳过创建集合: {}", collectionName);
            return false;
        }
        try {
            CreateCollectionReq req = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(schema)
                    .indexParams(indexParams)
                    .build();
            milvusClient.createCollection(req);
            initializedCollections.add(collectionName);
            log.info("集合创建成功(含索引): {}", collectionName);
            return true;
        } catch (Exception e) {
            log.error("创建集合失败(含索引): {}", collectionName, e);
            return false;
        }
    }

    /**
     * 确保集合存在（自动创建），返回是否刚创建
     *
     * @param collectionName 集合名称
     * @param schema         集合 schema
     * @return true-刚创建，false-已存在或创建失败
     */
    public boolean ensureCollection(String collectionName, CreateCollectionReq.CollectionSchema schema) {
        if (!isAvailable()) {
            return false;
        }
        if (initializedCollections.contains(collectionName)) {
            return false;
        }
        try {
            if (!hasCollection(collectionName)) {
                createCollection(collectionName, schema);
                return true;
            }
            initializedCollections.add(collectionName);
            return false;
        } catch (Exception e) {
            log.error("确保集合存在失败: {}", collectionName, e);
            return false;
        }
    }

    /**
     * 确保集合存在（带索引参数）
     *
     * @param collectionName 集合名称
     * @param schema         集合 schema
     * @param indexParams    索引参数
     * @return true-刚创建，false-已存在或创建失败
     */
    public boolean ensureCollection(String collectionName, CreateCollectionReq.CollectionSchema schema,
            List<IndexParam> indexParams) {
        if (!isAvailable()) {
            return false;
        }
        if (initializedCollections.contains(collectionName)) {
            return false;
        }
        try {
            if (!hasCollection(collectionName)) {
                createCollection(collectionName, schema, indexParams);
                return true;
            }
            initializedCollections.add(collectionName);
            return false;
        } catch (Exception e) {
            log.error("确保集合存在失败: {}", collectionName, e);
            return false;
        }
    }

    /**
     * 删除集合
     *
     * @param collectionName 集合名称
     * @return true-删除成功，false-失败或不可用
     */
    public boolean dropCollection(String collectionName) {
        if (!isAvailable()) {
            return false;
        }
        try {
            milvusClient.dropCollection(io.milvus.v2.service.collection.request.DropCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            initializedCollections.remove(collectionName);
            log.info("集合删除成功: {}", collectionName);
            return true;
        } catch (Exception e) {
            log.error("删除集合失败: {}", collectionName, e);
            return false;
        }
    }

    /**
     * 标记集合为已初始化（用于外部创建集合后同步缓存）
     *
     * @param collectionName 集合名称
     */
    public void markCollectionInitialized(String collectionName) {
        initializedCollections.add(collectionName);
    }

    /**
     * 清除集合初始化缓存（用于集合重建后重新检测）
     *
     * @param collectionName 集合名称
     */
    public void clearCollectionCache(String collectionName) {
        initializedCollections.remove(collectionName);
    }

    // ==================== 数据操作 ====================

    /**
     * 插入数据
     *
     * @param collectionName 集合名称
     * @param data           数据列表
     * @return true-成功，false-失败
     */
    public boolean insert(String collectionName, List<JsonObject> data) {
        if (!isAvailable()) {
            return false;
        }
        try {
            InsertReq req = InsertReq.builder()
                    .collectionName(collectionName)
                    .data(data)
                    .build();
            milvusClient.insert(req);
            return true;
        } catch (Exception e) {
            log.error("插入数据失败: collection={}, count={}", collectionName, data.size(), e);
            return false;
        }
    }

    /**
     * Upsert 数据（主键存在则更新，不存在则插入）
     *
     * @param collectionName 集合名称
     * @param data           数据列表
     * @return true-成功
     */
    public boolean upsert(String collectionName, List<JsonObject> data) {
        if (!isAvailable()) {
            return false;
        }
        try {
            UpsertReq req = UpsertReq.builder()
                    .collectionName(collectionName)
                    .data(data)
                    .build();
            milvusClient.upsert(req);
            return true;
        } catch (Exception e) {
            log.error("Upsert数据失败: collection={}, count={}", collectionName, data.size(), e);
            return false;
        }
    }

    /**
     * 批量 upsert 数据（分批提交）
     *
     * @param collectionName 集合名称
     * @param dataList       数据列表
     * @param batchSize      批次大小
     */
    public void upsertBatch(String collectionName, List<JsonObject> dataList, int batchSize) {
        if (!isAvailable() || dataList == null || dataList.isEmpty()) {
            return;
        }
        int totalSize = dataList.size();
        int offset = 0;
        while (offset < totalSize) {
            int end = Math.min(offset + batchSize, totalSize);
            List<JsonObject> batch = dataList.subList(offset, end);
            try {
                UpsertReq req = UpsertReq.builder()
                        .collectionName(collectionName)
                        .data(batch)
                        .build();
                milvusClient.upsert(req);
                log.info("upsert {}/{} 条数据到集合 {}", offset + batch.size(), totalSize, collectionName);
            } catch (Exception e) {
                log.warn("批量upsert失败: collection={}, offset={}", collectionName, offset, e);
            }
            offset = end;
        }
    }

    /**
     * 向量搜索
     *
     * @param collectionName 集合名称
     * @param vector         查询向量
     * @param topK           返回结果数量
     * @param searchFields   返回字段
     * @param filterExpr     过滤表达式（可选）
     * @return 搜索结果列表，失败返回空列表
     */
    public List<SearchResp.SearchResult> search(String collectionName, List<Float> vector,
            int topK, List<String> searchFields,
            @Nullable String filterExpr) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        try {
            SearchReq.SearchReqBuilder reqBuilder = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .topK(topK)
                    .outputFields(searchFields);
            if (filterExpr != null && !filterExpr.isEmpty()) {
                reqBuilder.filter(filterExpr);
            }
            SearchResp resp = milvusClient.search(reqBuilder.build());
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            if (results != null && !results.isEmpty()) {
                return results.get(0);
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("向量搜索失败: collection={}", collectionName, e);
            return Collections.emptyList();
        }
    }

    /**
     * 高级向量搜索（支持 annsField 等自定义参数）
     *
     * @param collectionName 集合名称
     * @param vector         查询向量
     * @param annsField      向量字段名
     * @param topK           返回结果数量
     * @param filterExpr     过滤表达式（可选）
     * @param outputFields   返回字段
     * @return 搜索结果列表，失败返回空列表
     */
    public List<SearchResp.SearchResult> search(String collectionName, List<Float> vector,
            String annsField, int topK,
            @Nullable String filterExpr,
            List<String> outputFields) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        try {
            SearchReq.SearchReqBuilder reqBuilder = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .annsField(annsField)
                    .topK(topK)
                    .outputFields(outputFields);
            if (filterExpr != null && !filterExpr.isEmpty()) {
                reqBuilder.filter(filterExpr);
            }
            SearchResp resp = milvusClient.search(reqBuilder.build());
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            if (results != null && !results.isEmpty()) {
                return results.get(0);
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("向量搜索失败: collection={}, annsField={}", collectionName, annsField, e);
            return Collections.emptyList();
        }
    }

    /**
     * 删除数据
     *
     * @param collectionName 集合名称
     * @param filterExpr     过滤表达式
     * @return true-成功
     */
    public boolean delete(String collectionName, String filterExpr) {
        if (!isAvailable()) {
            return false;
        }
        try {
            DeleteReq req = DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter(filterExpr)
                    .build();
            milvusClient.delete(req);
            return true;
        } catch (Exception e) {
            log.error("删除数据失败: collection={}", collectionName, e);
            return false;
        }
    }

    // ==================== 向量嵌入 ====================

    /**
     * 获取向量维度
     *
     * @return 向量维度
     */
    public int getEmbeddingDimension() {
        return embeddingService.getDimension();
    }

    /**
     * 单条文本向量化
     *
     * @param text 输入文本
     * @return 向量列表，失败返回空列表
     */
    public List<Float> embed(String text) {
        try {
            return embeddingService.embed(text);
        } catch (Exception e) {
            log.error("文本向量化失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 批量文本向量化（带重试和 zero-fill 降级）
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        return embeddingBatchService.embedBatchWithRetry(texts);
    }

    // ==================== 配置 ====================

    /**
     * 获取 Milvus 配置
     *
     * @return Milvus 配置对象
     */
    public MilvusConfig getConfig() {
        return milvusConfig;
    }
}
