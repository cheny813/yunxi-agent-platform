package io.yunxi.runtime.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;

/**
 * 单条 RAG 文档分块（产品平面语义，不携带任何 AgentScope-Java 框架类型）。
 *
 * <p>RAG 检索的本质是「跨会话持久化的文档分块」，每条分块以（tenant, collection, userId, chunkId）为自然键做
 * 实体键化存储，写入即去重，不产生同名多份。与长期记忆事实不同，分块无 entityId/factType
 * 维度——它的相关性由内容关键词 + 时间衰减 + 向量余弦决定，而非用户画像的实体/类型命中。</p>
 *
 * <p>集群正确性约束（与长期记忆事实同源）：{@code id} 由自然键经 SHA-256 确定性派生，保证同一自然键在
 * 任意实例写入都得到同一 id，使各后端 save 成为幂等 upsert，消除写分叉。租户维度已落地：自然键首位为
 * {@code tenant}，保证同一（collection, userId, chunkId）在不同租户下得到不同 id，从存储主键层面
 * 实现租户隔离；所有 save/query 均按 tenant 过滤，跨租户不可见。</p>
 *
 * @param tenant 租户标识，多租户隔离的首级维度（自然键首位）
 * @param id 分块唯一标识，由自然键确定性派生（非随机 UUID），保证跨实例幂等 upsert
 * @param collection 知识库集合名（命名空间，隔离不同文档集）
 * @param userId 所属用户标识，RAG 按用户隔离（与长期记忆一致）
 * @param chunkId 分块在集合内的逻辑标识（同文档同段稳定）
 * @param content 分块正文
 * @param embedding 稠密向量（可选，Milvus 后端使用；进程内默认忽略）
 * @param createdAt 创建时间，用于时间衰减与 TTL
 * @param ttlDays 存活天数，超过则被整理作业判定过期
 */
public record DocumentChunk(
        String tenant,
        String id,
        String collection,
        String userId,
        String chunkId,
        String content,
        float[] embedding,
        Instant createdAt,
        int ttlDays) {

    /**
     * 基于自然键生成一条新分块（id 由自然键确定性派生，createdAt 在此生成）。
     *
     * @param tenant 租户标识
     * @param collection 知识库集合名
     * @param userId 用户标识
     * @param chunkId 分块逻辑标识
     * @param content 分块正文
     * @param embedding 稠密向量（可空）
     * @param ttlDays 存活天数
     * @return 新分块
     */
    public static DocumentChunk of(String tenant, String collection, String userId, String chunkId,
                                   String content, float[] embedding, int ttlDays) {
        String id = storageKey(tenant, collection, userId, chunkId);
        return new DocumentChunk(tenant, id, collection, userId, chunkId, content,
                embedding, Instant.now(), ttlDays);
    }

    /**
     * 自然键（租户、集合、用户、分块）拼接，用于实体键化去重与冲突分组。
     * 采用长度前缀编码（"长度:值" + "|" 分隔），消除 "#" 等分隔符歧义导致的自然键碰撞——
     * 例如（"a","b#c","d","e"）与（"a#b","c","d","e"）在明文 "#" 拼接下会得到相同自然键、
     * 进而相同存储主键，造成跨租户覆盖；长度前缀使解析无歧义。各存储共用同一拼接规则（单一真相源）。
     * 分量允许为 null（单实例 / 单租户回落），统一按空串编码。
     */
    public static String naturalKey(String tenant, String collection, String userId, String chunkId) {
        return encode(tenant) + "|" + encode(collection) + "|" + encode(userId) + "|" + encode(chunkId);
    }

    /** 长度前缀编码：每个分量表示为 "长度:值"，配合 "|" 分隔，解析无歧义。 */
    private static String encode(String s) {
        String v = s == null ? "" : s;
        return v.length() + ":" + v;
    }

    /**
     * 存储主键：自然键的确定性 32 位十六进制摘要（SHA-256 截断）。同一自然键恒得同一主键，
     * 使各后端 save 成为幂等 upsert，从根上消除「集群并发写同一自然键产生多份分块」的分叉。
     * 自然键首位为 tenant，使不同租户的同（collection, userId, chunkId）得到不同主键，实现租户隔离。
     *
     * @param tenant 租户标识
     * @param collection 知识库集合名
     * @param userId 用户标识
     * @param chunkId 分块逻辑标识
     * @return 32 位十六进制存储主键
     */
    public static String storageKey(String tenant, String collection, String userId, String chunkId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(naturalKey(tenant, collection, userId, chunkId)
                    .getBytes(StandardCharsets.UTF_8));
            return toHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用，RAG 分块存储主键无法派生", e);
        }
    }

    /** 字节数组转小写十六进制字符串（避免依赖 JDK 各版本 HexFormat 重载差异）。 */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 以显式 id 构造分块（不经过自然键哈希），供向量存储直接以逻辑主键索引，或自存储反序列化重建。
     *
     * @param tenant 租户标识
     * @param id 分块唯一标识（即向量索引主键）
     * @param collection 知识库集合名
     * @param userId 用户标识
     * @param chunkId 分块逻辑标识
     * @param content 分块正文
     * @param ttlDays 存活天数
     */
    public static DocumentChunk create(String tenant, String id, String collection, String userId,
                                      String chunkId, String content, int ttlDays) {
        return new DocumentChunk(tenant, id, collection, userId, chunkId, content, null, Instant.now(), ttlDays);
    }

    /** 序列化为 JSON（正排内容；embedding 不落 BaseStore，仅存于向量索引）。 */
    public String toJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("tenant", tenant);
            node.put("id", id);
            node.put("collection", collection);
            node.put("userId", userId);
            node.put("chunkId", chunkId);
            node.put("content", content);
            node.put("createdAt", createdAt.toEpochMilli());
            node.put("ttlDays", ttlDays);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("DocumentChunk 序列化失败", e);
        }
    }

    /** 反序列化（解析失败返回 empty，不抛异常以免污染检索结果）。 */
    public static Optional<DocumentChunk> fromJson(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(json);
            String tenant = node.has("tenant") ? node.get("tenant").asText() : null;
            return Optional.of(new DocumentChunk(
                    tenant,
                    node.get("id").asText(),
                    node.get("collection").asText(),
                    node.get("userId").asText(),
                    node.get("chunkId").asText(),
                    node.get("content").asText(),
                    null,
                    Instant.ofEpochMilli(node.get("createdAt").asLong()),
                    node.get("ttlDays").asInt()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
