package io.yunxi.runtime.rag;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 向量检索驱动的 DocumentChunkStore 实现（仅向量模式）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>正排内容以 {@link BaseStore} 为唯一真相源（命名空间 {@code ["yunxi-rag", collection]}），向量仅作为派生索引；</li>
 *   <li>写入时先嵌入文本再写向量索引（{@link VectorIndex}），并同步落 BaseStore；</li>
 *   <li>检索时先嵌入查询，走向量最近邻，再回查 BaseStore 还原内容；</li>
 *   <li>向量库不可用（Milvus 缺失/未启动）时，检索/写入将抛出明确异常，由上层工具返回友好提示（不静默降级到关键词）。</li>
 * </ul>
 *
 * 当 {@code yunxi.rag.enabled=true} 且依赖齐备时，本类作为主键 {@code DocumentChunkStore} Bean，
 * 取代关键词版的 {@link SharedStoreBackedDocumentChunkStore}。
 */
@Component
@Primary
@ConditionalOnProperty(name = "yunxi.rag.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({BaseStore.class, EmbeddingClient.class, VectorIndex.class})
public class VectorBackedChunkStore implements DocumentChunkStore {

    private static final Logger log = LoggerFactory.getLogger(VectorBackedChunkStore.class);
    private static final List<String> NAMESPACE_PREFIX = List.of("yunxi-rag");

    private final BaseStore store;
    private final EmbeddingClient embedding;
    private final VectorIndex index;

    public VectorBackedChunkStore(BaseStore store,
                                  EmbeddingClient embedding,
                                  @Qualifier("vectorIndex") VectorIndex index) {
        this.store = store;
        this.embedding = embedding;
        this.index = index;
    }

    @Override
    public void save(DocumentChunk chunk) {
        float[] vector = embedding.embed(chunk.content());
        index.upsert(chunk.id(), chunk.tenant(), chunk.collection(), chunk.userId(), vector);
        store.put(namespace(chunk.collection()), chunk.id(),
                Map.of("json", chunk.toJson()));
        log.debug("[RAG] 向量写入 chunk={}, collection={}, user={}", chunk.id(), chunk.collection(), chunk.userId());
    }

    @Override
    public List<DocumentChunk> findRelevant(String tenant, String collection, String userId, String query, int topK) {
        float[] qv = embedding.embed(query);
        // 检索键纳入 tenant：多租户且 userId 撞车（如都走 anonymous）时，仅召回本租户分块，保证完整召回。
        List<String> ids = index.search(tenant, collection, userId, qv, topK);
        List<DocumentChunk> out = new ArrayList<>();
        for (String id : ids) {
            StoreItem item = store.get(namespace(collection), id);
            if (item != null) {
                DocumentChunk.fromJson((String) item.value().get("json"))
                        .filter(c -> Objects.equals(c.tenant(), tenant))
                        .ifPresent(out::add);
            }
        }
        return out;
    }

    @Override
    public void delete(String chunkId) {
        Optional<String> collection = resolveCollection(chunkId);
        index.delete(chunkId);
        collection.ifPresent(c -> store.delete(namespace(c), chunkId));
    }

    @Override
    public List<DocumentChunk> findAll(String tenant) {
        List<DocumentChunk> out = new ArrayList<>();
        for (StoreItem si : store.search(NAMESPACE_PREFIX, 0, Integer.MAX_VALUE)) {
            Object json = si.value().get("json");
            if (json instanceof String s) {
                DocumentChunk.fromJson(s)
                        .filter(c -> Objects.equals(c.tenant(), tenant))
                        .ifPresent(out::add);
            }
        }
        return out;
    }

    private Optional<String> resolveCollection(String chunkId) {
        // BaseStore 无按主键反查命名空间的能力，扫描 yunxi-rag 下所有记录定位所属 collection。
        for (StoreItem si : store.search(NAMESPACE_PREFIX, 0, Integer.MAX_VALUE)) {
            if (chunkId.equals(si.key())) {
                Object json = si.value().get("json");
                if (json instanceof String s) {
                    return DocumentChunk.fromJson(s).map(DocumentChunk::collection);
                }
            }
        }
        return Optional.empty();
    }

    private List<String> namespace(String collection) {
        List<String> ns = new ArrayList<>(NAMESPACE_PREFIX);
        ns.add(collection);
        return ns;
    }
}
