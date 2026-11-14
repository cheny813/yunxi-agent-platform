package io.yunxi.runtime.rag;

import java.util.List;

/**
 * RAG 文档分块存储抽象（产品平面检索后端单一真相源）。
 *
 * <p>RAG 检索后端复用与长期记忆同源的「融合打分 + 时间衰减」检索平面，但分块维度
 * （collection/userId/chunkId）与长期记忆的事实维度（userId/entityId/factType）不同，故独立定义接口，
 * 不把文档语义强加于长期记忆模型。进程内 / Redis / Milvus 三种后端经 store-type 条件化互斥，
 * 与长期记忆后端策略一致。</p>
 */
public interface DocumentChunkStore {

    /** 保存 / 更新一个分块（同自然键幂等 upsert）。 */
    void save(DocumentChunk chunk);

    /**
     * 检索感知召回：在指定租户、集合与用户下，按 query 做相关性融合打分，取前 topK。
     * query 为空时退化为按时间（越新越前）。跨租户不可见——结果严格按 tenant 过滤。
     *
     * @param tenant 租户标识（隔离首级维度）
     * @param collection 知识库集合名
     * @param userId 用户标识
     * @param query 检索问句（可空）
     * @param topK 返回条数（&le;0 视为不限）
     * @return 按相关性降序的分块列表
     */
    List<DocumentChunk> findRelevant(String tenant, String collection, String userId, String query, int topK);

    /** 按分块主键删除。 */
    void delete(String chunkId);

    /** 枚举指定租户下的全部分块（观测 / 整理用）。跨租户不可见。 */
    List<DocumentChunk> findAll(String tenant);
}
