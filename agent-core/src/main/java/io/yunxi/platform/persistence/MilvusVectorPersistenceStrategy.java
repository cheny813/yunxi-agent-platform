package io.yunxi.platform.persistence;

import io.yunxi.platform.config.MilvusConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.embedding.EmbeddingService;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.spi.vector.VectorPersistenceProvider;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.agentscope.core.message.Msg;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.common.IndexBuildState;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Milvus 向量数据库持久化策略
 *
 * <p>基于 Milvus 实现向量存储，支持语义搜索</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class MilvusVectorPersistenceStrategy implements DataPersistenceStrategy, VectorPersistenceProvider {

    private final Gson gson = new Gson();
    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final MilvusConfig milvusConfig;

    /**
     * 构造 Milvus 向量持久化策略。
     *
     * @param milvusClient      Milvus 客户端（v2）
     * @param embeddingService  文本向量化服务
     * @param milvusConfig      Milvus 配置（含向量维度等）
     */
    public MilvusVectorPersistenceStrategy(MilvusClientV2 milvusClient, EmbeddingService embeddingService, MilvusConfig milvusConfig) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.milvusConfig = milvusConfig;
    }

    /**
     * 初始化 Milvus 集合（带指数退避重试，自愈）
     * <p>启动时自动检查并创建 conversation_memory 集合，定义向量维度等 schema。
     * Milvus 可能尚未就绪，使用指数退避重试最多 5 次（2s → 4s → 8s → 16s → 32s），
     * 全部失败后仅记录错误日志，不阻塞 Spring 容器启动——后续每次记忆操作仍可独立重试。</p>
     */
    @PostConstruct
    public void init() {
        int maxRetries = 5;
        long delayMs = 2000;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                boolean isNew = !milvusClient.hasCollection(
                        HasCollectionReq.builder().collectionName("conversation_memory").build());

                if (isNew) {
                    // 新建集合：带向量索引一起创建，避免 loadCollection 时报 "index not found"
                    int dim = milvusConfig.getEmbedding().getDimension();
                    CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                            .fieldSchemaList(Arrays.asList(
                                    CreateCollectionReq.FieldSchema.builder().name("id").dataType(DataType.VarChar).maxLength(256).isPrimaryKey(true).autoID(false).build(),
                                    CreateCollectionReq.FieldSchema.builder().name("userId").dataType(DataType.VarChar).maxLength(256).build(),
                                    CreateCollectionReq.FieldSchema.builder().name("conversationId").dataType(DataType.VarChar).maxLength(256).build(),
                                    CreateCollectionReq.FieldSchema.builder().name("content").dataType(DataType.VarChar).maxLength(8192).build(),
                                    CreateCollectionReq.FieldSchema.builder().name("embedding").dataType(DataType.FloatVector).dimension(dim).build(),
                                    CreateCollectionReq.FieldSchema.builder().name("createdAt").dataType(DataType.Int64).build()))
                            .build();
                    List<IndexParam> indexParams = buildIndexParams("embedding");
                    milvusClient.createCollection(CreateCollectionReq.builder()
                            .collectionName("conversation_memory")
                            .collectionSchema(schema)
                            .indexParams(indexParams)
                            .build());
                    log.info("Milvus conversation_memory 集合（含索引）创建成功");
                    // 新建集合后等待索引构建完成（空集合会立即 Finished），避免紧接着 load 时索引未就绪
                    waitIndexReady("conversation_memory", "embedding");
                } else {
                    // 已有集合：检测是否缺少向量索引，缺少则补建（常见于旧版代码创建的无索引集合）
                    ensureVectorIndex("conversation_memory", "embedding");
                }

                // 显式 load 到内存——Milvus 重启后集合会处于未加载状态
                loadCollectionWithRetry("conversation_memory", 3, 2000);
                log.info("Milvus conversation_memory 集合初始化成功");
                return;
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    log.warn("Milvus 集合初始化失败（第 {}/{} 次），{}ms 后重试: {}",
                            attempt + 1, maxRetries, delayMs, e.getMessage());
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    delayMs = Math.min(delayMs * 2, 60000);
                } else {
                    log.error("Milvus 集合初始化失败，已达最大重试次数 {}，后续记忆操作将独立重试", maxRetries, e);
                }
            }
        }
    }

    /**
     * 保存会话数据
     * <p>将会话中的所有消息进行向量嵌入后存入 Milvus</p>
     *
     * @param conversation 会话实体（包含消息列表）
     * @return true 保存成功，false 保存失败
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        try {
            saveMemory(conversation.getId(), conversation.getMessages(), null);
            return true;
        } catch (Exception e) {
            log.error("Milvus 保存会话失败", e);
            return false;
        }
    }

    /**
     * 删除指定会话的所有记忆数据
     *
     * @param conversationId 会话 ID
     * @return true 删除成功，false 删除失败
     */
    @Override
    public boolean deleteConversation(String conversationId) {
        try {
            milvusClient.delete(DeleteReq.builder().collectionName("conversation_memory").filter("conversationId == '" + conversationId + "'").build());
            return true;
        } catch (Exception e) {
            log.error("Milvus 删除会话失败", e);
            return false;
        }
    }

    /**
     * 保存会话记忆
     * <p>将消息列表转换为向量嵌入后批量存入 Milvus，每条消息对应一条向量记录</p>
     *
     * @param conversationId 会话 ID
     * @param messages       消息列表
     * @param config         记忆配置（当前未使用）
     * @return true 保存成功，false 保存失败
     */
    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        try {
            if (messages == null || messages.isEmpty()) return true;
            List<JsonObject> rows = new ArrayList<>();
            for (Msg msg : messages) {
                String text = msg.getTextContent();
                if (text == null || text.isEmpty()) continue;
                List<Float> embedding = embeddingService.embed(text);
                JsonObject row = new JsonObject();
                row.addProperty("id", UUID.randomUUID().toString());
                row.addProperty("conversationId", conversationId);
                row.addProperty("content", text);
                row.add("embedding", gson.toJsonTree(embedding));
                row.addProperty("createdAt", System.currentTimeMillis());
                rows.add(row);
            }
            milvusClient.insert(InsertReq.builder().collectionName("conversation_memory").data(rows).build());
            return true;
        } catch (Exception e) {
            log.error("Milvus 保存记忆失败", e);
            return false;
        }
    }

    /**
     * 获取会话相关的记忆
     * <p>用最新一条消息的向量做语义搜索，返回 topK 条最相似的记忆内容</p>
     *
     * @param conversationId 会话 ID
     * @param config         记忆配置（maxContextSize 控制返回条数）
     * @return 相似记忆消息列表，失败时返回空列表
     */
    @Override
    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        try {
            if (conversationMapper == null) return List.of();
            // 通过 conversationId 查询所属会话实体
            ConversationEntity entity = conversationMapper.findById(conversationId);
            if (entity == null || entity.getMessages() == null || entity.getMessages().isEmpty())
                return List.of();
            List<Msg> allMsgs = entity.getMessages();
            String latestText = allMsgs.get(allMsgs.size() - 1).getTextContent();
            List<Float> embedding = embeddingService.embed(latestText);
            SearchResp resp = milvusClient.search(SearchReq.builder()
                    .collectionName("conversation_memory").data(Collections.singletonList(new FloatVec(embedding)))
                    .topK(config.getMaxContextSize() > 0 ? config.getMaxContextSize() : 10)
                    .outputFields(Collections.singletonList("content")).build());
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            List<Msg> resultMsgs = new ArrayList<>();
            for (SearchResp.SearchResult r : results) {
                Object contentObj = r.getEntity().get("content");
                if (contentObj != null) {
                    resultMsgs.add(Msg.builder().textContent(contentObj.toString()).build());
                }
            }
            return resultMsgs;
        } catch (Exception e) {
            log.error("Milvus 获取记忆失败", e);
            return List.of();
        }
    }

    /**
     * 删除指定会话的记忆
     * <p>与删除会话等价，直接删除该会话下所有向量记录</p>
     *
     * @param conversationId 会话 ID
     * @return true 删除成功
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        return deleteConversation(conversationId);
    }

    /**
     * 获取策略名称
     *
     * @return 策略名称标识 "MilvusVector"
     */
    @Override
    public String getStrategyName() {
        return "MilvusVector";
    }

    /**
     * 获取策略类型
     *
     * @return 策略类型 ARCHIVE（归档型持久化）
     */
    @Override
    public StrategyType getStrategyType() {
        return StrategyType.ARCHIVE;
    }

    // ─── VectorPersistenceProvider 接口实现 ───

    /**
     * 保存用户记忆
     * <p>将文本内容向量化后存入 Milvus，关联用户 ID 和元数据</p>
     *
     * @param userId   用户 ID
     * @param content  记忆文本内容
     * @param metadata 附加元数据（可选）
     * @return true 保存成功，false 保存失败
     */
    @Override
    public boolean saveUserMemory(String userId, String content, Map<String, Object> metadata) {
        try {
            List<Float> embedding = embeddingService.embed(content);
            JsonObject row = new JsonObject();
            row.addProperty("id", UUID.randomUUID().toString());
            row.addProperty("userId", userId);
            row.addProperty("content", content);
            row.add("embedding", gson.toJsonTree(embedding));
            row.addProperty("createdAt", System.currentTimeMillis());
            if (metadata != null)
                metadata.forEach((k, v) -> {
                    if (v instanceof String s) row.addProperty(k, s);
                    else if (v instanceof Number n) row.addProperty(k, n);
                    else if (v instanceof Boolean b) row.addProperty(k, b);
                    else if (v instanceof Character c) row.addProperty(k, c);
                    else row.addProperty(k, String.valueOf(v));
                });
            milvusClient.insert(InsertReq.builder().collectionName("conversation_memory").data(List.of(row)).build());
            return true;
        } catch (Exception e) {
            log.error("Milvus 保存用户记忆失败", e);
            return false;
        }
    }

    /**
     * 语义搜索相似记忆
     * <p>将查询文本向量化后，在 Milvus 中进行 ANN 搜索，返回 topK 条最相似的记忆</p>
     *
     * @param query  查询文本
     * @param userId 用户 ID（当前搜索未按用户过滤）
     * @param topK   返回的最相似结果数量
     * @return 相似记忆搜索结果列表，包含内容、相似度得分和创建时间
     */
    @Override
    public List<SearchResult> searchSimilarMemory(String query, String userId, int topK) {
        try {
            List<Float> embedding = embeddingService.embed(query);
            SearchResp resp = milvusClient.search(SearchReq.builder()
                    .collectionName("conversation_memory").data(Collections.singletonList(new FloatVec(embedding)))
                    .topK(topK > 0 ? topK : 10)
                    .outputFields(List.of("content", "userId", "createdAt")).build());
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            List<SearchResult> resultList = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                SearchResp.SearchResult r = results.get(i);
                Object contentObj = r.getEntity().get("content");
                if (contentObj != null) {
                    SearchResult sr = new SearchResult();
                    sr.setId(String.valueOf(r.getId()));
                    sr.setContent(contentObj.toString());
                    sr.setScore(r.getScore());
                    Object createdAt = r.getEntity().get("createdAt");
                    if (createdAt instanceof Number num) sr.setCreatedAt(num.longValue());
                    resultList.add(sr);
                }
            }
            return resultList;
        } catch (Exception e) {
            log.error("Milvus 相似记忆搜索失败", e);
            return List.of();
        }
    }

    /**
     * 批量存储向量
     * <p>将一批向量数据按 ID 批量插入指定集合</p>
     *
     * @param collection 目标集合名称
     * @param ids        向量 ID 列表
     * @param vectors    向量数据列表（每项为一个浮点向量）
     * @param metadata   附加元数据（可选 key-value 对）
     */
    public void storeVectors(String collection, List<String> ids, List<List<Float>> vectors, Map<String, String> metadata) {
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            JsonObject row = new JsonObject();
            row.addProperty("id", ids.get(i));
            row.add("embedding", gson.toJsonTree(vectors.get(i)));
            if (metadata != null)
                metadata.forEach(row::addProperty);
            rows.add(row);
        }
        milvusClient.insert(InsertReq.builder().collectionName(collection).data(rows).build());
    }

    /**
     * 向量相似搜索
     * <p>根据查询向量在指定集合中搜索最相似的 topK 条记录（当前未实现）</p>
     *
     * @param collection  目标集合名称
     * @param queryVector 查询向量
     * @param topK        返回数量
     * @return 空列表（待实现）
     */
    public List<String> searchSimilar(String collection, List<Float> queryVector, int topK) {
        return List.of();
    }

    /**
     * 设置会话 Mapper（Spring 注入）
     * <p>用于从数据库读取会话数据以辅助记忆检索</p>
     *
     * @param conversationMapper 会话数据访问映射器
     */
    @Autowired(required = false)
    public void setConversationMapper(io.yunxi.platform.shared.mapper.ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    private io.yunxi.platform.shared.mapper.ConversationMapper conversationMapper;

    /**
     * 将集合加载到 Milvus 内存（带重试）。
     * <p>Milvus 重启后已存在的集合会处于未加载状态，必须显式 load 才能查询/搜索。
     * loadCollection 是异步操作，通过轮询 getLoadState 等待完成。</p>
     *
     * @param collectionName 集合名称
     * @param maxRetries     最大重试次数
     * @param delayMs        重试间隔（毫秒）
     */
    private void loadCollectionWithRetry(String collectionName, int maxRetries, long delayMs) {
        long loadTimeoutMs = 60_000; // loadCollection 异步，最多等 60s
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                milvusClient.loadCollection(LoadCollectionReq.builder()
                        .collectionName(collectionName).build());
                // 轮询等待加载完成
                long start = System.currentTimeMillis();
                while (true) {
                    boolean loaded = Boolean.TRUE.equals(milvusClient.getLoadState(
                            GetLoadStateReq.builder().collectionName(collectionName).build()));
                    if (loaded) {
                        long elapsed = System.currentTimeMillis() - start;
                        log.info("集合已加载到内存: {} (耗时 {}ms)", collectionName, elapsed);
                        return;
                    }
                    if (System.currentTimeMillis() - start > loadTimeoutMs) {
                        log.warn("集合加载超时 ({}ms): {}", loadTimeoutMs, collectionName);
                        break; // 跳出轮询，进入下次重试
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("集合加载被中断: {}", collectionName);
                return;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                // 如果因为缺少索引导致加载失败，尝试补建索引后重试
                if (msg.contains("index not found")) {
                    log.warn("集合 {} 缺少向量索引，尝试自动补建", collectionName);
                    try {
                        ensureVectorIndex(collectionName, "embedding");
                        // 索引补建成功后，本次 attempt 不计入重试，直接重试 load
                        attempt = Math.max(attempt - 1, -1);
                        continue;
                    } catch (Exception idxEx) {
                        log.error("自动补建索引失败: {}", collectionName, idxEx);
                    }
                }
                if (attempt < maxRetries - 1) {
                    log.warn("集合加载失败（第 {}/{} 次），{}ms 后重试: {}",
                            attempt + 1, maxRetries, delayMs, msg);
                    try { Thread.sleep(delayMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                } else {
                    log.error("集合加载失败，已达最大重试次数 {}: {}", maxRetries, collectionName, e);
                }
            }
        }
    }

    /**
     * 确保集合的向量字段有索引。
     * <p>优先用 {@code listIndexes}（无索引时返回空列表、不会在 SDK 内部报 ERROR）判断是否存在索引，
     * 避免旧逻辑用 {@code describeIndex} 探测"无索引"时产生误导性的 ERROR 日志。
     * 若索引缺失则按配置创建（索引类型/度量/nlist 全部取自 milvus.embedding 配置，
     * 修正此前硬编码 COSINE 与配置 IP 不一致的问题）。</p>
     *
     * @param collectionName 集合名称
     * @param vectorField    向量字段名
     */
    private void ensureVectorIndex(String collectionName, String vectorField) {
        if (hasVectorIndex(collectionName)) {
            log.info("集合 {} 已存在向量索引，跳过创建", collectionName);
            return;
        }
        try {
            milvusClient.createIndex(CreateIndexReq.builder()
                    .collectionName(collectionName)
                    .indexParams(buildIndexParams(vectorField))
                    .build());
            log.info("已为集合 {} 的字段 {} 创建向量索引", collectionName, vectorField);
            // 对已存在的非空集合，createIndex 是异步的，等待索引真正构建完成再返回，
            // 避免调用方紧接着 loadCollection 时索引尚未就绪而失败
            waitIndexReady(collectionName, vectorField);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            // 并发或 listIndexes 误判导致已存在索引时，忽略 "already exist" 类错误
            if (msg.contains("exist")) {
                log.info("集合 {} 的字段 {} 索引已存在（跳过）", collectionName, vectorField);
                return;
            }
            log.error("为集合 {} 创建索引失败: {}", collectionName, e.getMessage());
            throw new RuntimeException("索引创建失败: " + collectionName, e);
        }
    }

    /**
     * 轮询等待集合向量索引构建完成（带超时）。
     * <p>对已存在的非空集合，{@code createIndex} 是异步操作，索引未构建完时 loadCollection 会失败。
     * 通过轮询 {@link DescribeIndexReq} 的索引状态，直到 {@link IndexBuildState#Finished}
     * （或超时/失败）再返回，消除"建索引→立即 load 因索引未就绪而失败"的竞态。</p>
     *
     * @param collectionName 集合名称
     * @param vectorField    向量字段名
     */
    private void waitIndexReady(String collectionName, String vectorField) {
        long deadline = System.currentTimeMillis() + milvusConfig.getIndexBuildTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            // 仅在确认集合已有索引时才 describeIndex，避免索引尚未注册时触发 SDK 内部 "index not found" ERROR 噪音
            if (hasVectorIndex(collectionName)) {
            try {
                DescribeIndexResp resp = milvusClient.describeIndex(DescribeIndexReq.builder()
                        .collectionName(collectionName).fieldName(vectorField).build());
                IndexBuildState state = null;
                List<DescribeIndexResp.IndexDesc> descs =
                        resp != null ? resp.getIndexDescriptions() : null;
                if (descs != null) {
                    for (DescribeIndexResp.IndexDesc desc : descs) {
                        if (vectorField.equals(desc.getFieldName())) {
                            state = desc.getIndexState();
                            break;
                        }
                    }
                }
                if (state == IndexBuildState.Finished) {
                    log.info("集合 {} 的字段 {} 索引已构建完成", collectionName, vectorField);
                    return;
                }
                if (state == IndexBuildState.Failed) {
                    log.error("集合 {} 的字段 {} 索引构建失败，将跳过等待: {}", collectionName, vectorField, descs);
                    return;
                }
            } catch (Exception e) {
                // 索引刚创建、尚未在 describe 中可见的短暂窗口内可能抛 "index not found"，继续等待
                log.debug("查询索引状态失败，继续等待: {}", e.getMessage());
            }
            }
            // sleep 放在 if 之外，保证未检测到索引的轮询早期也会按节拍休眠，避免忙等
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("等待索引构建被中断: {}", collectionName);
                return;
            }
        }
        log.warn("等待集合 {} 的字段 {} 索引构建超时（{}ms），继续尝试加载", collectionName, vectorField, milvusConfig.getIndexBuildTimeoutMs());
    }

    /**
     * 判断集合是否已存在向量索引（无索引时返回 false，且不会触发 SDK 内部 ERROR 日志）。
     *
     * @param collectionName 集合名称
     * @return 是否存在至少一个索引
     */
    private boolean hasVectorIndex(String collectionName) {
        try {
            List<String> indexNames = milvusClient.listIndexes(
                    ListIndexesReq.builder().collectionName(collectionName).build());
            return indexNames != null && !indexNames.isEmpty();
        } catch (Exception e) {
            log.debug("listIndexes 失败，视为无索引: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 按配置构建向量索引参数（索引类型/相似度度量/nlist）。
     *
     * @param vectorField 向量字段名
     * @return 索引参数列表
     */
    private List<IndexParam> buildIndexParams(String vectorField) {
        return List.of(IndexParam.builder()
                .fieldName(vectorField)
                .indexType(resolveIndexType(milvusConfig.getEmbedding().getIndexType()))
                .metricType(resolveMetricType(milvusConfig.getEmbedding().getMetricType()))
                .extraParams(Map.of("nlist", String.valueOf(milvusConfig.getEmbedding().getNlist())))
                .build());
    }

    private IndexParam.IndexType resolveIndexType(String type) {
        if (type == null || type.isBlank()) return IndexParam.IndexType.IVF_FLAT;
        try {
            return IndexParam.IndexType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return IndexParam.IndexType.IVF_FLAT;
        }
    }

    private IndexParam.MetricType resolveMetricType(String type) {
        if (type == null || type.isBlank()) return IndexParam.MetricType.COSINE;
        try {
            return IndexParam.MetricType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return IndexParam.MetricType.COSINE;
        }
    }
}
