package io.yunxi.runtime.rag;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VectorBackedChunkStore 行为测试：向量检索路径 + 正排(BaseStore) 作为唯一真相源。 */
class VectorBackedChunkStoreTest {

    private static final String TEST_TENANT = "t-1";

    private static final class FakeEmbedding implements EmbeddingClient {
        @Override
        public float[] embed(String text) {
            // 确定性伪嵌入：以文本字符和长度构造二维向量，保证查询与命中文本可比对。
            float x = text.hashCode() % 1000 / 1000f;
            float y = text.length() / 100f;
            return new float[] { x, y };
        }
    }

    /** 内存版 BaseStore 替身（与 SharedStoreBackedDocumentChunkStoreTest 同构）。 */
    private static final class MemStore implements BaseStore {
        private final Map<String, StoreItem> map = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public StoreItem get(List<String> namespace, String key) {
            return map.get(String.join("/", namespace) + "/" + key);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            map.put(String.join("/", namespace) + "/" + key, new StoreItem(key, value));
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int offset, int limit) {
            String p = String.join("/", namespace) + "/";
            List<StoreItem> out = new java.util.ArrayList<>();
            map.forEach((k, v) -> {
                if (k.startsWith(p)) {
                    out.add(v);
                }
            });
            return out;
        }

        @Override
        public void delete(List<String> namespace, String key) {
            map.remove(String.join("/", namespace) + "/" + key);
        }
    }

    private DocumentChunk chunk(String id, String collection, String userId, String content) {
        return DocumentChunk.create(TEST_TENANT, id, collection, userId, id, content, 30);
    }

    @Test
    void saveThenFindRelevant_returnsStoredContent() {
        BaseStore store = new MemStore();
        EmbeddingClient embedding = new FakeEmbedding();
        VectorIndex index = new InMemoryVectorIndexFake();
        VectorBackedChunkStore chunkStore = new VectorBackedChunkStore(store, embedding, index);

        chunkStore.save(chunk("c1", "kb", "u1", "分布式系统的共识算法"));
        chunkStore.save(chunk("c2", "kb", "u1", "向量数据库的近似最近邻检索"));

        List<DocumentChunk> hits = chunkStore.findRelevant(TEST_TENANT, "kb", "u1", "向量检索怎么做", 5);

        assertFalse(hits.isEmpty());
        // 查询文本与 c2 语义更接近，应被召回。
        assertTrue(hits.stream().anyMatch(c -> c.id().equals("c2")));
        assertTrue(hits.stream().allMatch(c -> TEST_TENANT.equals(c.tenant())));
    }

    @Test
    void findRelevant_isolatedByCollectionAndUser() {
        BaseStore store = new MemStore();
        VectorBackedChunkStore chunkStore =
                new VectorBackedChunkStore(store, new FakeEmbedding(), new InMemoryVectorIndexFake());

        chunkStore.save(chunk("a1", "kb-a", "u1", "alpha content about databases"));
        chunkStore.save(chunk("b1", "kb-b", "u1", "beta content about databases"));
        chunkStore.save(chunk("a2", "kb-a", "u2", "alpha content about databases"));

        List<DocumentChunk> hits = chunkStore.findRelevant(TEST_TENANT, "kb-a", "u1", "databases", 10);

        assertEquals(1, hits.size());
        assertEquals("a1", hits.get(0).id());
    }

    @Test
    void delete_removesFromIndexAndStore() {
        BaseStore store = new MemStore();
        InMemoryVectorIndexFake index = new InMemoryVectorIndexFake();
        VectorBackedChunkStore chunkStore = new VectorBackedChunkStore(store, new FakeEmbedding(), index);

        DocumentChunk c = chunk("d1", "kb", "u1", "to be deleted");
        chunkStore.save(c);
        chunkStore.delete(c.id());

        assertTrue(chunkStore.findRelevant(TEST_TENANT, "kb", "u1", "to be deleted", 5).isEmpty());
    }

    @Test
    void findAll_returnsAllStored() {
        BaseStore store = new MemStore();
        VectorBackedChunkStore chunkStore =
                new VectorBackedChunkStore(store, new FakeEmbedding(), new InMemoryVectorIndexFake());

        chunkStore.save(chunk("x1", "kb", "u1", "one"));
        chunkStore.save(chunk("x2", "kb", "u1", "two"));

        assertEquals(2, chunkStore.findAll(TEST_TENANT).size());
    }

    @Test
    void crossTenantChunksAreInvisible() {
        BaseStore store = new MemStore();
        VectorBackedChunkStore chunkStore =
                new VectorBackedChunkStore(store, new FakeEmbedding(), new InMemoryVectorIndexFake());

        chunkStore.save(DocumentChunk.create("tenantA", "cA", "kb", "u1", "cA", "租户A的内容", 30));
        chunkStore.save(DocumentChunk.create("tenantB", "cB", "kb", "u1", "cB", "租户B的内容", 30));

        List<DocumentChunk> hitsA = chunkStore.findRelevant("tenantA", "kb", "u1", "内容", 5);
        assertTrue(hitsA.stream().allMatch(c -> "tenantA".equals(c.tenant())));
        assertTrue(hitsA.stream().noneMatch(c -> "tenantB".equals(c.tenant())));

        List<DocumentChunk> hitsB = chunkStore.findRelevant("tenantB", "kb", "u1", "内容", 5);
        assertTrue(hitsB.stream().allMatch(c -> "tenantB".equals(c.tenant())));

        DocumentChunk a = DocumentChunk.create("tenantA", "cA", "kb", "u1", "cA", "x", 30);
        DocumentChunk b = DocumentChunk.create("tenantB", "cB", "kb", "u1", "cB", "x", 30);
        assertNotEquals(a.id(), b.id(), "不同租户的分块主键应不同");
    }

    @Test
    void crossTenantUserIdCollision_retainsFullRecallForOwner() {
        // W4 回归：多租户且 userId 撞车（都走同一 userId）时，向量检索须把 tenant 纳入检索键，
        // 保证租户自有分块的完整召回，而非被跨租户、且语义更接近的分块挤占 topK 槽位。
        BaseStore store = new MemStore();
        VectorBackedChunkStore chunkStore =
                new VectorBackedChunkStore(store, new FakeEmbedding(), new InMemoryVectorIndexFake());
        String sharedUser = "anonymous";
        String collection = "kb";
        // tenantA 内容与查询语义更接近（会竞争 topK 槽位）。
        for (int i = 0; i < 3; i++) {
            chunkStore.save(DocumentChunk.create("tenantA", "a" + i, collection, sharedUser, "a" + i,
                    "深度学习模型训练与推理优化", 30));
        }
        // tenantB 内容语义偏离查询（旧实现下 sim 低于 A，易被挤出 topK）。
        for (int i = 0; i < 3; i++) {
            chunkStore.save(DocumentChunk.create("tenantB", "b" + i, collection, sharedUser, "b" + i,
                    "机器学习算法基础", 30));
        }

        List<DocumentChunk> hitsB = chunkStore.findRelevant("tenantB", collection, sharedUser,
                "深度学习模型训练与推理优化", 5);

        assertEquals(3, hitsB.size(), "租户 userId 撞车时，本租户分块须完整召回，不被跨租户挤占 topK");
        assertTrue(hitsB.stream().allMatch(c -> "tenantB".equals(c.tenant())));
        assertTrue(hitsB.stream().noneMatch(c -> "tenantA".equals(c.tenant())));
    }

    @Test
    void inMemoryIndex_searchFiltersByTenant_underUserIdCollision() {
        // W4 回归（索引层）：两租户同 collection + 同 userId + 相同向量时，search 必须仅召回本租户。
        InMemoryVectorIndexFake index = new InMemoryVectorIndexFake();
        float[] v = { 1.0f, 0.0f };
        index.upsert("a1", "tenantA", "kb", "anonymous", v);
        index.upsert("a2", "tenantA", "kb", "anonymous", v);
        index.upsert("b1", "tenantB", "kb", "anonymous", v);
        index.upsert("b2", "tenantB", "kb", "anonymous", v);

        List<String> hits = index.search("tenantB", "kb", "anonymous", v, 10);

        assertEquals(2, hits.size());
        assertTrue(hits.contains("b1") && hits.contains("b2"));
        assertTrue(hits.stream().noneMatch(id -> id.startsWith("a")));
    }
}
