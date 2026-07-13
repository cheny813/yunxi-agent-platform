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
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
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
     * 初始化 Milvus 集合
     * <p>启动时自动检查并创建 conversation_memory 集合，定义向量维度等 schema</p>
     */
    @PostConstruct
    public void init() {
        if (!milvusClient.hasCollection(HasCollectionReq.builder().collectionName("conversation_memory").build())) {
            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .fieldSchemaList(Arrays.asList(
                            CreateCollectionReq.FieldSchema.builder().name("id").dataType(DataType.VarChar).maxLength(256).isPrimaryKey(true).autoID(false).build(),
                            CreateCollectionReq.FieldSchema.builder().name("userId").dataType(DataType.VarChar).maxLength(256).build(),
                            CreateCollectionReq.FieldSchema.builder().name("conversationId").dataType(DataType.VarChar).maxLength(256).build(),
                            CreateCollectionReq.FieldSchema.builder().name("content").dataType(DataType.VarChar).maxLength(8192).build(),
                            CreateCollectionReq.FieldSchema.builder().name("embedding").dataType(DataType.FloatVector).dimension(milvusConfig.getEmbedding().getDimension()).build(),
                            CreateCollectionReq.FieldSchema.builder().name("createdAt").dataType(DataType.Int64).build()))
                    .build();
            milvusClient.createCollection(CreateCollectionReq.builder().collectionName("conversation_memory").collectionSchema(schema).build());
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
}
