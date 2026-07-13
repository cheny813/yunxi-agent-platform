package io.yunxi.platform.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import io.yunxi.platform.embedding.EmbeddingService;
import io.yunxi.platform.persistence.milvus.MilvusOperations;

import java.util.List;

/**
 * 数据同步服务基类
 * <p>
 * 提供公用的数据库查询、数据解析、向量化、插入功能。
 * 数据同步通过 JDBC 直连目标数据库完成。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public abstract class BaseSyncService {

    /** 日志记录器（protected 以子类使用） */
    protected static final Logger log = LoggerFactory.getLogger(BaseSyncService.class);

    /** Milvus 操作门面 */
    protected final MilvusOperations milvusOps;

    /** 向量嵌入服务 */
    protected final EmbeddingService embeddingService;

    /** 外部数据库查询服务（通过 JDBC 直连） */
    protected final ExternalDbQueryService externalDbQueryService;

    /** Milvus 集合管理服务 */
    protected final MilvusCollectionService milvusCollectionService;

    /** 向量嵌入批量服务 */
    protected final EmbeddingBatchService embeddingBatchService;

    /** JSON 对象映射器 */
    protected final ObjectMapper objectMapper = new ObjectMapper();

    /** Gson 序列化器 */
    protected final Gson gson = new GsonBuilder().create();

    /**
     * 构造函数
     *
     * @param milvusOps               Milvus 操作门面
     * @param embeddingService        向量嵌入服务
     * @param externalDbQueryService  外部数据库查询服务
     * @param milvusCollectionService Milvus 集合管理服务
     * @param embeddingBatchService   向量嵌入批量服务
     */
    public BaseSyncService(
            MilvusOperations milvusOps,
            EmbeddingService embeddingService,
            ExternalDbQueryService externalDbQueryService,
            MilvusCollectionService milvusCollectionService,
            EmbeddingBatchService embeddingBatchService) {
        this.milvusOps = milvusOps;
        this.embeddingService = embeddingService;
        this.externalDbQueryService = externalDbQueryService;
        this.milvusCollectionService = milvusCollectionService;
        this.embeddingBatchService = embeddingBatchService;
    }

    // ==================== MilvusCollectionService 委托方法 ====================

    /**
     * 委托查询集合实体数量。
     *
     * @param collectionName 集合名称
     * @return 实体数量
     */
    protected long getCollectionCount(String collectionName) {
        return milvusCollectionService.getCollectionCount(collectionName);
    }

    /**
     * 委托判断集合是否存在。
     *
     * @param collectionName 集合名称
     * @return 存在返回 true
     */
    protected boolean isCollectionExists(String collectionName) {
        return milvusCollectionService.isCollectionExists(collectionName);
    }

    /**
     * 委托批量 upsert 数据到集合。
     *
     * @param collectionName 集合名称
     * @param dataList JSON 行数据
     * @param batchSize 每批大小
     */
    protected void upsertBatch(String collectionName, List<JsonObject> dataList, int batchSize) {
        milvusCollectionService.upsertBatch(collectionName, dataList, batchSize);
    }

    // ==================== 数据库查询委托方法 ====================

    /**
     * 查询外部数据库（直接 JDBC 连接，默认 limit=10000）
     *
     * @param jdbcUrl  JDBC URL
     * @param username 用户名
     * @param password 密码
     * @param sql      SQL 语句
     * @return 查询结果 JSON 字符串
     */
    protected String queryDatabase(String jdbcUrl, String username, String password, String sql) {
        return externalDbQueryService.query(jdbcUrl, username, password, sql);
    }

    /**
     * 查询外部数据库
     *
     * @param jdbcUrl  JDBC URL
     * @param username 用户名
     * @param password 密码
     * @param sql      SQL 语句
     * @param limit    返回数量限制
     * @return 查询结果 JSON 字符串
     */
    protected String queryDatabase(String jdbcUrl, String username, String password, String sql, int limit) {
        return externalDbQueryService.query(jdbcUrl, username, password, sql, limit);
    }

    // ==================== EmbeddingBatchService 委托方法 ====================

    /**
     * 委托批量向量化文本（带重试）。
     *
     * @param texts 待嵌入文本列表
     * @return 向量列表
     */
    protected List<List<Float>> embedBatchWithRetry(List<String> texts) {
        return embeddingBatchService.embedBatchWithRetry(texts);
    }

}
