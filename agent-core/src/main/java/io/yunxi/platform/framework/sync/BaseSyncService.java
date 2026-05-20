package io.yunxi.platform.framework.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.infra.milvus.MilvusOperations;

import java.util.List;

/**
 * 数据同步服务基类
 * <p>
 * 提供公共的 MCP 调用、数据解析、向量化、插入等功能
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public abstract class BaseSyncService {

    /** 日志记录器（protected 以便子类使用） */
    protected static final Logger log = LoggerFactory.getLogger(BaseSyncService.class);

    /** Milvus 操作门面 */
    protected final MilvusOperations milvusOps;
    /** 向量嵌入服务 */
    protected final EmbeddingService embeddingService;
    /** MCP 查询服务 */
    protected final McpQueryService mcpQueryService;
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
     * @param mcpQueryService         MCP 查询服务
     * @param milvusCollectionService Milvus 集合管理服务
     * @param embeddingBatchService   向量嵌入批量服务
     */
    public BaseSyncService(
            MilvusOperations milvusOps,
            EmbeddingService embeddingService,
            McpQueryService mcpQueryService,
            MilvusCollectionService milvusCollectionService,
            EmbeddingBatchService embeddingBatchService) {
        this.milvusOps = milvusOps;
        this.embeddingService = embeddingService;
        this.mcpQueryService = mcpQueryService;
        this.milvusCollectionService = milvusCollectionService;
        this.embeddingBatchService = embeddingBatchService;
    }

    // ==================== MilvusCollectionService 委托方法 ====================

    /**
     * 获取集合记录数
     *
     * @param collectionName 集合名称
     * @return 记录数量
     */
    protected long getCollectionCount(String collectionName) {
        return milvusCollectionService.getCollectionCount(collectionName);
    }

    /**
     * 检查集合是否存在
     *
     * @param collectionName 集合名称
     * @return true-存在，false-不存在
     */
    protected boolean isCollectionExists(String collectionName) {
        return milvusCollectionService.isCollectionExists(collectionName);
    }

    /**
     * 批量 upsert 数据到 Milvus（主键已存在则更新，不存在则插入）
     *
     * @param collectionName 集合名称
     * @param dataList       数据列表
     * @param batchSize      批次大小
     */
    protected void upsertBatch(String collectionName, List<JsonObject> dataList, int batchSize) {
        milvusCollectionService.upsertBatch(collectionName, dataList, batchSize);
    }

    // ==================== McpQueryService 委托方法 ====================

    /**
     * 通过 MCP 调用外部数据库执行 SQL 查询（默认 limit=10000）
     *
     * @param host MCP 服务地址
     * @param port MCP 服务端口
     * @param sql  SQL 查询语句
     * @return 查询结果
     */
    protected String callMcpDatabase(String host, int port, String sql) {
        return mcpQueryService.callMcpDatabase(host, port, sql);
    }

    /**
     * 通过 MCP 调用外部数据库执行 SQL 查询
     *
     * @param host  MCP 服务地址
     * @param port  MCP 服务端口
     * @param sql   SQL 查询语句
     * @param limit 返回结果数量限制
     * @return 查询结果
     */
    protected String callMcpDatabase(String host, int port, String sql, int limit) {
        return mcpQueryService.callMcpDatabase(host, port, sql, limit);
    }

    // ==================== EmbeddingBatchService 委托方法 ====================

    /**
     * 分批调用 embedding API（带重试）
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    protected List<List<Float>> embedBatchWithRetry(List<String> texts) {
        return embeddingBatchService.embedBatchWithRetry(texts);
    }

}
