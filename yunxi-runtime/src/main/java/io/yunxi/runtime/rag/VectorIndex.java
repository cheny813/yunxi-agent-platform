package io.yunxi.runtime.rag;

/**
 * 向量索引抽象：将 chunk 的向量写入索引并支持按集合/用户维度做最近邻检索。
 *
 * <p>实现与具体向量库（Milvus 等）解耦，便于在不依赖外部基础设施的前提下做单测。
 * 向量仅作为派生索引，正排内容始终以 {@link BaseStore} 为唯一真相源（向量可依据正排内容重建，不构成第二真相源）。
 */
public interface VectorIndex {

    /**
     * 写入/覆盖一条向量记录。
     *
     * @param id chunk 主键（已含 tenant，由 {@code DocumentChunk.storageKey} 派生）
     * @param tenant 租户标识（隔离首级维度，纳入检索过滤键，避免跨租户 userId 撞车挤占 topK）
     * @param collection 知识库集合名
     * @param userId 用户标识
     * @param vector 稠密向量
     */
    void upsert(String id, String tenant, String collection, String userId, float[] vector);

    /**
     * 在指定租户、集合与用户范围内做最近邻检索，返回命中的 chunk id（按相似度降序，至多 topK 条）。
     * 检索键纳入 tenant：多租户且 userId 撞车（如都走 anonymous）时，仅召回本租户分块，
     * 保证租户自有分块的完整召回，而非被跨租户分块挤占 topK 槽位（独立评审 W4 修复）。
     */
    java.util.List<String> search(String tenant, String collection, String userId, float[] query, int topK);

    /** 按主键删除一条向量记录。 */
    void delete(String id);
}
