package io.yunxi.platform.infra.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.agentscope.core.message.Msg;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import io.yunxi.platform.infra.config.MilvusConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.spi.vector.VectorPersistenceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Milvus 向量数据库持久化策略
 *
 * <p>
 * 实现基于 Milvus 的向量存储，支持语义检索
 * </p>
 *
 * <p>
 * <b>数据结构</b>：
 * <ul>
 * <li>id: 消息/会话唯一标识</li>
 * <li>userId: 用户ID（用于按用户检索）</li>
 * <li>conversationId: 会话ID</li>
 * <li>contentType: 内容类型（message/recipe/preference）</li>
 * <li>content: 原始文本内容</li>
 * <li>metadata: 元数据（JSON格式）</li>
 * <li>embedding: 向量表示</li>
 * <li>createdAt: 创建时间戳</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>架构分层</b>：实现 {@link VectorPersistenceProvider} SPI 接口，允许上层通过接口访问
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class MilvusVectorPersistenceStrategy implements DataPersistenceStrategy, VectorPersistenceProvider {

    /** Milvus 客户端 */
    private final MilvusClientV2 milvusClient;
    /** Milvus 配置 */
    private final MilvusConfig config;
    /** 向量嵌入服务 */
    private final EmbeddingService embeddingService;
    /** JSON 序列化工具 */
    private final Gson gson = new Gson();

    /** 字段名常量：主键 ID */
    private static final String FIELD_ID = "id";
    /** 字段名常量：用户 ID */
    private static final String FIELD_USER_ID = "userId";
    /** 字段名常量：会话 ID */
    private static final String FIELD_CONVERSATION_ID = "conversationId";
    /** 字段名常量：内容类型 */
    private static final String FIELD_CONTENT_TYPE = "contentType";
    /** 字段名常量：内容 */
    private static final String FIELD_CONTENT = "content";
    /** 字段名常量：元数据 */
    private static final String FIELD_METADATA = "metadata";
    /** 字段名常量：向量 */
    private static final String FIELD_EMBEDDING = "embedding";
    /** 字段名常量：创建时间 */
    private static final String FIELD_CREATED_AT = "createdAt";

    /**
     * 构造 Milvus 向量持久化策略
     *
     * @param milvusClient     Milvus 客户端
     * @param config           Milvus 配置
     * @param embeddingService 向量嵌入服务
     */
    @Autowired
    public MilvusVectorPersistenceStrategy(
            MilvusClientV2 milvusClient,
            MilvusConfig config,
            EmbeddingService embeddingService) {
        this.milvusClient = milvusClient;
        this.config = config;
        this.embeddingService = embeddingService;
    }

    /**
     * 初始化：创建向量集合（如果不存在）
     */
    @PostConstruct
    public void init() {
        if (milvusClient == null) {
            log.warn("Milvus 客户端未初始化，向量存储功能禁用");
            return;
        }

        try {
            // 先检测实际向量维度
            detectAndSyncDimension();

            createCollectionIfNotExists(config.getCollections().getUserMemory());
            createCollectionIfNotExists(config.getCollections().getConversationMemory());
            log.info("Milvus 向量存储策略初始化完成，向量维度: {}", config.getEmbedding().getDimension());
        } catch (Exception e) {
            log.error("Milvus 初始化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 检测实际向量维度并同步到配置
     * 通过一次测试调用来确定 Embedding API 返回的实际维度
     */
    private void detectAndSyncDimension() {
        try {
            // 使用测试文本获取实际向量维度
            List<Float> testEmbedding = embeddingService.embed("dimension_test");
            if (testEmbedding != null && !testEmbedding.isEmpty()) {
                int actualDimension = testEmbedding.size();
                int configDimension = config.getEmbedding().getDimension();

                if (actualDimension != configDimension) {
                    log.info("检测到向量维度差异: 配置={}, 实际={}, 已自动同步配置",
                            configDimension, actualDimension);
                    config.getEmbedding().updateDimension(actualDimension);
                } else {
                    log.debug("向量维度验证通过: {}", actualDimension);
                }
            }
        } catch (Exception e) {
            log.warn("无法检测实际向量维度，使用配置值: {} - {}",
                    config.getEmbedding().getDimension(), e.getMessage());
        }
    }

    /**
     * 创建向量集合
     */
    private void createCollectionIfNotExists(String collectionName) {
        try {
            // 检查集合是否存在
            HasCollectionReq hasReq = HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build();
            boolean exists = milvusClient.hasCollection(hasReq);

            if (exists) {
                log.debug("向量集合已存在: {}", collectionName);
                return;
            }

            // 定义字段结构
            CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                    .name(FIELD_ID)
                    .dataType(DataType.VarChar)
                    .maxLength(256)
                    .isPrimaryKey(true)
                    .autoID(false)
                    .description("记忆唯一标识")
                    .build();

            CreateCollectionReq.FieldSchema userIdField = CreateCollectionReq.FieldSchema.builder()
                    .name(FIELD_USER_ID)
                    .dataType(DataType.VarChar)
                    .maxLength(256)
                    .description("用户ID")
                    .build();

            CreateCollectionReq.FieldSchema conversationIdField = CreateCollectionReq.FieldSchema.builder()
                    .name(FIELD_CONVERSATION_ID)
                    .dataType(DataType.VarChar)
                    .maxLength(256)
                    .description("会话ID")
                    .build();

            CreateCollectionReq.FieldSchema contentTypeField = CreateCollectionReq.FieldSchema.builder()
                    .name(FIELD_CONTENT_TYPE)
                    .dataType(DataType.VarChar)
                    .maxLength(64)
                    .description("内容类型")
                    .build();

            CreateCollectionReq.FieldSchema contentField = CreateCollectionReq.FieldSchema.builder()
                    .name(FIELD_CONTENT)
                    .dataType(DataType.VarChar)
                    .maxLength(8192)
                    .description("记忆内容")
                    .build();

            CreateCollectionReq.FieldSchema metadataField = CreateCollectionReq.FieldSchema.builder()
                    .name(FIELD_METADATA)
                    .dataType(DataType.JSON)
                    .description("元数据")
                    .build();

            CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                    .name(FIELD_EMBEDDING)
                    .dataType(DataType.FloatVector)
                    .dimension(config.getEmbedding().getDimension())
                    .description("内容向量")
                    .build();

            CreateCollectionReq.FieldSchema createdAtField = CreateCollectionReq.FieldSchema.builder()
                    .name(FIELD_CREATED_AT)
                    .dataType(DataType.Int64)
                    .description("创建时间戳")
                    .build();

            // 创建集合 Schema
            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .fieldSchemaList(Arrays.asList(
                            idField, userIdField, conversationIdField,
                            contentTypeField, contentField, metadataField,
                            embeddingField, createdAtField))
                    .build();

            // 创建索引参数
            IndexParam indexParam = IndexParam.builder()
                    .fieldName(FIELD_EMBEDDING)
                    .indexType(IndexParam.IndexType.IVF_FLAT)
                    .metricType(IndexParam.MetricType.IP)
                    .extraParams(Map.of("nlist", String.valueOf(config.getEmbedding().getNlist())))
                    .build();

            // 集合描述
            String description = collectionName.contains("conversation")
                    ? "会话记忆数据"
                    : "用户记忆数据";

            CreateCollectionReq createReq = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .description(description)
                    .collectionSchema(schema)
                    .indexParams(Collections.singletonList(indexParam))
                    .build();

            milvusClient.createCollection(createReq);
            log.info("创建向量集合成功: {}", collectionName);

        } catch (Exception e) {
            log.error("创建向量集合失败: {}, error: {}", collectionName, e.getMessage(), e);
        }
    }

    /**
     * 从消息列表提取文本内容
     */
    private String extractTextFromMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Msg msg : messages) {
            if (msg.getContent() != null) {
                // 检查内容类型
                Object content = msg.getContent();
                if (content instanceof String) {
                    sb.append(content).append(" ");
                } else {
                    // 如果是其他类型（如 ContentBlock），转为字符串
                    sb.append(content.toString()).append(" ");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 保存会话到向量数据库（自动处理维度不匹配）
     *
     * @param conversation 会话实体
     * @return 保存成功返回 true
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        try {
            // 向量化会话标题和摘要
            String text = conversation.getTitle();
            String messagesText = extractTextFromMessages(conversation.getMessages());
            if (!messagesText.isEmpty()) {
                text += " " + messagesText;
            }

            if (text == null || text.trim().isEmpty()) {
                log.warn("会话内容为空，跳过保存: conversationId={}", conversation.getId());
                return false;
            }

            List<Float> embedding = embeddingService.embed(text);
            if (embedding == null || embedding.isEmpty()) {
                log.warn("向量化失败，跳过保存: conversationId={}", conversation.getId());
                return false;
            }

            // 构建插入数据（使用 JsonObject）
            JsonObject data = new JsonObject();
            data.addProperty(FIELD_ID, conversation.getId());
            data.addProperty(FIELD_USER_ID,
                    conversation.getUserId() != null ? conversation.getUserId() : "default-user");
            data.addProperty(FIELD_CONVERSATION_ID, conversation.getId());
            data.addProperty(FIELD_CONTENT_TYPE, "conversation");
            data.addProperty(FIELD_CONTENT, text);
            data.add(FIELD_METADATA, gson.toJsonTree(Map.of(
                    "agentName", conversation.getAgentName() != null ? conversation.getAgentName() : "",
                    "messageCount", conversation.getMessageCount())));
            data.add(FIELD_EMBEDDING, gson.toJsonTree(embedding));
            data.addProperty(FIELD_CREATED_AT, System.currentTimeMillis());

            InsertReq insertReq = InsertReq.builder()
                    .collectionName(config.getCollections().getConversationMemory())
                    .data(Collections.singletonList(data))
                    .build();

            milvusClient.insert(insertReq);
            log.debug("保存会话向量成功: conversationId={}", conversation.getId());
            return true;

        } catch (Exception e) {
            // 检查是否是维度不匹配错误（通用检测）
            if (isDimensionMismatchError(e.getMessage())) {
                log.warn("检测到向量维度不匹配，正在重建集合: {}", e.getMessage());
                try {
                    // 更新配置中的维度为实际维度
                    int actualDimension = extractActualDimension(e.getMessage());
                    if (actualDimension > 0) {
                        config.getEmbedding().updateDimension(actualDimension);
                        log.info("已更新向量维度配置为: {}", actualDimension);
                    }

                    // 删除旧集合
                    String collectionName = config.getCollections().getConversationMemory();
                    DropCollectionReq dropReq = DropCollectionReq.builder()
                            .collectionName(collectionName)
                            .build();
                    milvusClient.dropCollection(dropReq);
                    log.info("已删除维度不匹配的向量集合: {}", collectionName);

                    // 重建集合（使用更新后的维度）
                    createCollectionIfNotExists(collectionName);
                    log.info("已重建向量集合，重试保存操作: {}", collectionName);

                    // 重试插入
                    String text = conversation.getTitle();
                    String messagesText = extractTextFromMessages(conversation.getMessages());
                    if (!messagesText.isEmpty()) {
                        text += " " + messagesText;
                    }

                    if (text == null || text.trim().isEmpty()) {
                        log.warn("会话内容为空，跳过保存: conversationId={}", conversation.getId());
                        return false;
                    }

                    List<Float> embedding = embeddingService.embed(text);
                    if (embedding == null || embedding.isEmpty()) {
                        log.warn("向量化失败，跳过保存: conversationId={}", conversation.getId());
                        return false;
                    }

                    // 构建插入数据（使用 JsonObject）
                    JsonObject data = new JsonObject();
                    data.addProperty(FIELD_ID, conversation.getId());
                    data.addProperty(FIELD_USER_ID,
                            conversation.getUserId() != null ? conversation.getUserId() : "default-user");
                    data.addProperty(FIELD_CONVERSATION_ID, conversation.getId());
                    data.addProperty(FIELD_CONTENT_TYPE, "conversation");
                    data.addProperty(FIELD_CONTENT, text);
                    data.add(FIELD_METADATA, gson.toJsonTree(Map.of(
                            "agentName", conversation.getAgentName() != null ? conversation.getAgentName() : "",
                            "messageCount", conversation.getMessageCount())));
                    data.add(FIELD_EMBEDDING, gson.toJsonTree(embedding));
                    data.addProperty(FIELD_CREATED_AT, System.currentTimeMillis());

                    InsertReq insertReq = InsertReq.builder()
                            .collectionName(config.getCollections().getConversationMemory())
                            .data(Collections.singletonList(data))
                            .build();

                    milvusClient.insert(insertReq);
                    log.info("重试保存会话向量成功: conversationId={}", conversation.getId());
                    return true;
                } catch (Exception retryException) {
                    log.error("重建集合后重试保存失败: {}", retryException.getMessage(), retryException);
                    return false;
                }
            }

            log.error("保存会话向量失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 删除会话向量
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    @Override
    public boolean deleteConversation(String conversationId) {
        try {
            DeleteReq deleteReq = DeleteReq.builder()
                    .collectionName(config.getCollections().getConversationMemory())
                    .filter(FIELD_CONVERSATION_ID + " == \"" + conversationId + "\"")
                    .build();

            milvusClient.delete(deleteReq);
            log.debug("删除会话向量成功: conversationId={}", conversationId);
            return true;

        } catch (Exception e) {
            log.error("删除会话向量失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 保存记忆消息到向量数据库
     *
     * @param conversationId 会话 ID
     * @param messages       消息列表
     * @param memConfig      记忆配置
     * @return 保存成功返回 true
     */
    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig memConfig) {
        try {
            List<JsonObject> dataList = new ArrayList<>();

            for (Msg msg : messages) {
                String text = extractTextFromMessages(Collections.singletonList(msg));
                if (text == null || text.trim().isEmpty())
                    continue;

                List<Float> embedding = embeddingService.embed(text);
                if (embedding == null || embedding.isEmpty())
                    continue;

                JsonObject data = new JsonObject();
                data.addProperty(FIELD_ID, UUID.randomUUID().toString());
                data.addProperty(FIELD_USER_ID, "default-user");
                data.addProperty(FIELD_CONVERSATION_ID, conversationId);
                data.addProperty(FIELD_CONTENT_TYPE, "message");
                data.addProperty(FIELD_CONTENT, text);
                data.add(FIELD_METADATA, gson.toJsonTree(Map.of(
                        "role", msg.getRole() != null ? msg.getRole() : "unknown")));
                data.add(FIELD_EMBEDDING, gson.toJsonTree(embedding));
                data.addProperty(FIELD_CREATED_AT, System.currentTimeMillis());

                dataList.add(data);
            }

            if (dataList.isEmpty()) {
                return true;
            }

            InsertReq insertReq = InsertReq.builder()
                    .collectionName(config.getCollections().getConversationMemory())
                    .data(dataList)
                    .build();

            milvusClient.insert(insertReq);
            log.debug("保存记忆向量成功: conversationId={}, count={}", conversationId, dataList.size());
            return true;

        } catch (Exception e) {
            log.error("保存记忆向量失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取记忆（Milvus 主要用于语义检索，此处返回空列表）
     *
     * @param conversationId 会话 ID
     * @param memConfig      记忆配置
     * @return 空消息列表
     */
    @Override
    public List<Msg> getMemory(String conversationId, MemoryConfig memConfig) {
        // Milvus 主要用于语义检索，这里返回空列表
        // 具体的语义搜索通过 searchSimilarMemory 方法实现
        return Collections.emptyList();
    }

    /**
     * 语义搜索相似记忆
     *
     * @param query  查询文本
     * @param userId 用户ID（可为null）
     * @param topK   返回数量
     * @return 搜索结果列表
     */
    @Override
    public List<SearchResult> searchSimilarMemory(String query, String userId, int topK) {
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，无法执行语义搜索");
            return Collections.emptyList();
        }

        try {
            // 生成查询向量
            List<Float> queryVector = embeddingService.embed(query);
            if (queryVector == null || queryVector.isEmpty()) {
                log.warn("查询向量化失败: {}", query);
                return Collections.emptyList();
            }

            // 构建搜索请求 - Milvus SDK 2.4.x API
            // 使用 FloatVec 包装向量数据
            FloatVec queryFloatVec = new FloatVec(queryVector);
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(config.getCollections().getUserMemory())
                    .data(Collections.singletonList(queryFloatVec))
                    .annsField(FIELD_EMBEDDING)
                    .topK(topK)
                    .outputFields(Arrays.asList(FIELD_CONTENT, FIELD_METADATA, FIELD_CREATED_AT, FIELD_USER_ID))
                    .build();

            // 执行搜索
            SearchResp searchResp = milvusClient.search(searchReq);

            // 解析结果
            List<SearchResult> results = new ArrayList<>();
            List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();

            if (searchResults != null && !searchResults.isEmpty()) {
                for (SearchResp.SearchResult hit : searchResults.get(0)) {
                    SearchResult result = new SearchResult();
                    result.setScore(hit.getScore());
                    result.setCreatedAt((Long) hit.getEntity().get(FIELD_CREATED_AT));

                    Object contentObj = hit.getEntity().get(FIELD_CONTENT);
                    result.setContent(contentObj != null ? contentObj.toString() : "");

                    // 解析元数据
                    Object metadataObj = hit.getEntity().get(FIELD_METADATA);
                    if (metadataObj != null) {
                        try {
                            if (metadataObj instanceof JsonObject) {
                                result.setMetadata(gson.fromJson((JsonObject) metadataObj, Map.class));
                            } else {
                                result.setMetadata(Map.of("raw", metadataObj.toString()));
                            }
                        } catch (Exception e) {
                            result.setMetadata(new HashMap<>());
                        }
                    }

                    // 用户过滤
                    if (userId != null && !userId.isEmpty()) {
                        Object resultUserId = hit.getEntity().get(FIELD_USER_ID);
                        if (resultUserId != null && !userId.equals(resultUserId.toString())) {
                            continue;
                        }
                    }

                    results.add(result);
                }
            }

            log.debug("语义搜索完成: query={}, results={}", query, results.size());
            return results;

        } catch (Exception e) {
            log.error("语义搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 搜索业务历史向量数据
     *
     * @param query  查询文本
     * @param userId 用户 ID
     * @param topK   返回数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchBusinessHistory(String query, String userId, int topK) {
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，无法执行业务历史搜索");
            return Collections.emptyList();
        }

        try {
            List<Float> queryVector = embeddingService.embed(query);
            if (queryVector == null || queryVector.isEmpty()) {
                return Collections.emptyList();
            }

            // 构建带过滤条件的搜索
            String filter = FIELD_USER_ID + " == \"" + userId + "\"";

            FloatVec queryFloatVec = new FloatVec(queryVector);
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(config.getCollections().getBusinessHistory())
                    .data(Collections.singletonList(queryFloatVec))
                    .annsField(FIELD_EMBEDDING)
                    .topK(topK)
                    .filter(filter)
                    .outputFields(Arrays.asList(FIELD_CONTENT, FIELD_METADATA, FIELD_CREATED_AT))
                    .build();

            SearchResp searchResp = milvusClient.search(searchReq);

            List<SearchResult> results = new ArrayList<>();
            List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();

            if (searchResults != null && !searchResults.isEmpty()) {
                for (SearchResp.SearchResult hit : searchResults.get(0)) {
                    SearchResult result = new SearchResult();
                    result.setScore(hit.getScore());
                    result.setCreatedAt((Long) hit.getEntity().get(FIELD_CREATED_AT));

                    Object contentObj = hit.getEntity().get(FIELD_CONTENT);
                    result.setContent(contentObj != null ? contentObj.toString() : "");

                    Object metadataObj = hit.getEntity().get(FIELD_METADATA);
                    if (metadataObj != null) {
                        try {
                            if (metadataObj instanceof JsonObject) {
                                result.setMetadata(gson.fromJson((JsonObject) metadataObj, Map.class));
                            } else {
                                result.setMetadata(Map.of("raw", metadataObj.toString()));
                            }
                        } catch (Exception e) {
                            result.setMetadata(new HashMap<>());
                        }
                    }

                    results.add(result);
                }
            }

            log.debug("业务历史搜索完成: userId={}, query={}, results={}", userId, query, results.size());
            return results;

        } catch (Exception e) {
            log.error("业务历史搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 保存用户偏好/记忆到向量库
     *
     * @param userId   用户ID
     * @param content  内容
     * @param metadata 元数据
     * @return 是否成功
     */
    @Override
    public boolean saveUserMemory(String userId, String content, Map<String, Object> metadata) {
        try {
            List<Float> embedding = embeddingService.embed(content);
            if (embedding == null || embedding.isEmpty()) {
                return false;
            }

            // 智能去重检查
            String contentType = metadata != null ? (String) metadata.getOrDefault("type", "general") : "general";
            if (!shouldSkipDueToDeduplication(userId, content, contentType)) {
                JsonObject data = new JsonObject();
                data.addProperty(FIELD_ID, UUID.randomUUID().toString());
                data.addProperty(FIELD_USER_ID, userId);
                data.addProperty(FIELD_CONVERSATION_ID, "");
                data.addProperty(FIELD_CONTENT_TYPE, "user_memory");
                data.addProperty(FIELD_CONTENT, content);
                data.add(FIELD_METADATA, gson.toJsonTree(metadata != null ? metadata : Collections.emptyMap()));
                data.add(FIELD_EMBEDDING, gson.toJsonTree(embedding));
                data.addProperty(FIELD_CREATED_AT, System.currentTimeMillis());

                InsertReq insertReq = InsertReq.builder()
                        .collectionName(config.getCollections().getUserMemory())
                        .data(Collections.singletonList(data))
                        .build();

                milvusClient.insert(insertReq);
                log.debug("保存用户记忆成功: userId={}, type={}", userId, contentType);
            } else {
                log.debug("跳过保存重复记忆: userId={}, type={}", userId, contentType);
            }
            return true;

        } catch (Exception e) {
            // 检查是否是维度不匹配错误（通用检测）
            if (isDimensionMismatchError(e.getMessage())) {
                log.warn("检测到向量维度不匹配，正在重建集合: {}", e.getMessage());
                try {
                    // 更新配置中的维度为实际维度
                    int actualDimension = extractActualDimension(e.getMessage());
                    if (actualDimension > 0) {
                        config.getEmbedding().updateDimension(actualDimension);
                        log.info("已更新向量维度配置为: {}", actualDimension);
                    }

                    // 删除旧集合
                    String collectionName = config.getCollections().getUserMemory();
                    DropCollectionReq dropReq = DropCollectionReq.builder()
                            .collectionName(collectionName)
                            .build();
                    milvusClient.dropCollection(dropReq);
                    log.info("已删除维度不匹配的向量集合: {}", collectionName);

                    // 重建集合（使用更新后的维度）
                    createCollectionIfNotExists(collectionName);
                    log.info("已重建向量集合，重试保存操作: {}", collectionName);

                    // 重试插入
                    List<Float> embedding = embeddingService.embed(content);
                    if (embedding == null || embedding.isEmpty()) {
                        return false;
                    }

                    JsonObject data = new JsonObject();
                    data.addProperty(FIELD_ID, UUID.randomUUID().toString());
                    data.addProperty(FIELD_USER_ID, userId);
                    data.addProperty(FIELD_CONVERSATION_ID, "");
                    data.addProperty(FIELD_CONTENT_TYPE, "user_memory");
                    data.addProperty(FIELD_CONTENT, content);
                    data.add(FIELD_METADATA, gson.toJsonTree(metadata != null ? metadata : Collections.emptyMap()));
                    data.add(FIELD_EMBEDDING, gson.toJsonTree(embedding));
                    data.addProperty(FIELD_CREATED_AT, System.currentTimeMillis());

                    InsertReq insertReq = InsertReq.builder()
                            .collectionName(config.getCollections().getUserMemory())
                            .data(Collections.singletonList(data))
                            .build();

                    milvusClient.insert(insertReq);
                    log.info("重试保存用户记忆成功: userId={}", userId);
                    return true;
                } catch (Exception retryException) {
                    log.error("重建集合后重试保存失败: {}", retryException.getMessage(), retryException);
                    return false;
                }
            }

            log.error("保存用户记忆失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 智能去重检查：判断是否应该跳过保存
     * 
     * @param userId      用户ID
     * @param content     新内容
     * @param contentType 内容类型
     * @return true表示应该跳过保存，false表示应该保存
     */
    private boolean shouldSkipDueToDeduplication(String userId, String content, String contentType) {
        try {
            // 搜索相似内容
            List<SearchResult> similarResults = searchSimilarMemory(content, userId, 10);
            if (similarResults.isEmpty()) {
                return false; // 没有相似内容，允许保存
            }

            double similarityThreshold = getSimilarityThreshold(contentType);

            for (SearchResult result : similarResults) {
                double similarity = result.getScore();

                // 计算时间权重
                double timeWeight = calculateTimeWeight(result.getCreatedAt());
                double adjustedSimilarity = similarity * timeWeight;

                // 高相似度 + 最近时间 = 可能重复
                if (adjustedSimilarity >= similarityThreshold) {
                    // 检查内容是否有变化
                    if (!hasContentChanged(content, result)) {
                        // 检查是否是持续关注的重要信息
                        if (shouldSaveDespiteSimilarity(content, similarResults, contentType)) {
                            log.info("持续关注话题，允许保存: 相似度={}", similarity);
                            return false;
                        }
                        log.debug("检测到重复内容，跳过保存: 相似度={}, 时间权重={}, 调整后相似度={}",
                                similarity, timeWeight, adjustedSimilarity);
                        return true;
                    }
                }
            }

            return false; // 不满足跳过条件，允许保存

        } catch (Exception e) {
            log.warn("去重检查失败，允许保存: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 根据内容类型获取相似度阈值
     */
    private double getSimilarityThreshold(String contentType) {
        return switch (contentType) {
            case "preference", "interest", "fact", "knowledge" -> 0.95; // 偏好、知识：高阈值
            case "concern", "worry", "intent", "goal" -> 0.85; // 关注、意图：中低阈值
            case "question" -> 0.90; // 问题：中高阈值
            default -> 0.90; // 默认
        };
    }

    /**
     * 计算时间权重（时间衰减）
     */
    private double calculateTimeWeight(long recordTimestamp) {
        long age = System.currentTimeMillis() - recordTimestamp;
        long oneDay = 24 * 60 * 60 * 1000L;

        // 1天内的：权重1.0
        if (age < oneDay)
            return 1.0;
        // 1-7天：权重0.8
        if (age < oneDay * 7)
            return 0.8;
        // 7-30天：权重0.5
        if (age < oneDay * 30)
            return 0.5;
        // 30天以上：权重0.3
        return 0.3;
    }

    /**
     * 判断是否是最近的记录
     */
    private boolean isRecent(long timestamp) {
        long age = System.currentTimeMillis() - timestamp;
        return age < 30 * 24 * 60 * 60 * 1000L; // 30天内
    }

    /**
     * 检查内容是否有明显变化
     */
    private boolean hasContentChanged(String newContent, SearchResult existingResult) {
        String existingContent = existingResult.getContent();
        if (existingContent == null)
            return true;

        // 计算简单的相似度（基于长度差异）
        double similarity = calculateTextSimilarity(newContent, existingContent);

        // 如果相似度低于80%，视为有变化
        return similarity < 0.8;
    }

    /**
     * 计算文本相似度（简化版）
     */
    private double calculateTextSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null)
            return 0.0;

        // 简单的 Jaccard 相似度（基于单词集合）
        Set<String> words1 = Arrays.stream(text1.toLowerCase().split("\\s+"))
                .filter(w -> w.length() > 1)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> words2 = Arrays.stream(text2.toLowerCase().split("\\s+"))
                .filter(w -> w.length() > 1)
                .collect(java.util.stream.Collectors.toSet());

        Set<String> intersection = new java.util.HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new java.util.HashSet<>(words1);
        union.addAll(words2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * 判断是否因重要性而允许保存重复内容
     * 例如：持续关注的话题、执念、重要目标等
     */
    private boolean shouldSaveDespiteSimilarity(String content, List<SearchResult> similarResults, String contentType) {
        // 检查相似内容的最近出现频次
        long recentSimilarCount = similarResults.stream()
                .filter(r -> r.getScore() >= 0.80)
                .filter(r -> isRecent(r.getCreatedAt()))
                .count();

        // 关注类、意图类内容：如果最近出现3次以上，说明是持续关注，应该保存
        if (contentType.equals("concern") || contentType.equals("worry") ||
                contentType.equals("intent") || contentType.equals("goal")) {
            if (recentSimilarCount >= 3) {
                log.info("检测到持续关注话题，允许重复保存: type={}, 频次={}", contentType, recentSimilarCount);
                return true;
            }
        }

        // 问题类：如果最近出现5次以上，说明用户持续有此问题，应该保存
        if (contentType.equals("question") && recentSimilarCount >= 5) {
            log.info("检测到重复问题，允许保存: 频次={}", recentSimilarCount);
            return true;
        }

        return false;
    }

    /**
     * 检查是否是维度不匹配错误
     */
    private boolean isDimensionMismatchError(String message) {
        return message != null && message.contains("dimension")
                && message.contains("is not equal to field's dimension");
    }

    /**
     * 从错误消息中提取实际的向量维度
     */
    private int extractActualDimension(String message) {
        if (message == null)
            return -1;
        try {
            // 错误消息格式: "dimension: 1024 is not equal to field's dimension: 1536"
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("dimension:\\s*(\\d+)");
            java.util.regex.Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Exception e) {
            log.debug("无法从错误消息中提取维度: {}", message);
        }
        return -1;
    }

    /**
     * 删除记忆向量
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        try {
            DeleteReq deleteReq = DeleteReq.builder()
                    .collectionName(config.getCollections().getConversationMemory())
                    .filter(FIELD_CONVERSATION_ID + " == \"" + conversationId + "\"")
                    .build();

            milvusClient.delete(deleteReq);
            return true;

        } catch (Exception e) {
            log.error("删除记忆向量失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取策略名称
     *
     * @return 策略名称 "MilvusVector"
     */
    @Override
    public String getStrategyName() {
        return "MilvusVector";
    }

    /**
     * 获取策略类型
     *
     * @return ARCHIVE 归档类型
     */
    @Override
    public StrategyType getStrategyType() {
        return StrategyType.ARCHIVE;
    }

    /**
     * 搜索结果封装（使用 SPI 接口定义）
     */
    // SearchResult 类已移至
    // io.yunxi.platform.shared.spi.VectorPersistenceProvider.SearchResult
}
