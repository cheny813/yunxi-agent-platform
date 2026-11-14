package io.yunxi.runtime.rag;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SharedStoreBackedDocumentChunkStore} 行为测试：以框架共享存储抽象（内存实现）为后端，
 * 覆盖 save / findRelevant（关键词命中率 × 时间衰减）/ delete / findAll，以及按 userId、tenant 隔离。
 *
 * <p>测试替身 {@link InMemoryBaseStoreForTest} 仅用于本测试，不引入对 yunxi-fabric 的反向依赖。</p>
 */
class SharedStoreBackedDocumentChunkStoreTest {

    private static final String TEST_TENANT = "t-1";

    private SharedStoreBackedDocumentChunkStore store() {
        return new SharedStoreBackedDocumentChunkStore(new InMemoryBaseStoreForTest());
    }

    private DocumentChunk chunk(String id, String collection, String userId, String content) {
        return DocumentChunk.create(TEST_TENANT, id, collection, userId, id + "-ck", content, 180);
    }

    @Test
    void save_and_findRelevant_byKeyword() {
        SharedStoreBackedDocumentChunkStore store = store();
        store.save(chunk("c1", "kb", "u1", "Kubernetes 部署与滚动更新策略"));
        store.save(chunk("c2", "kb", "u1", "Redis 缓存雪崩应对方案"));
        store.save(chunk("c3", "kb", "u2", "Kubernetes 命名空间隔离"));

        List<DocumentChunk> hits = store.findRelevant(TEST_TENANT, "kb", "u1", "Kubernetes 部署", 5);
        assertFalse(hits.isEmpty(), "关键词命中的分块应被召回");
        assertEquals("c1", hits.get(0).id(), "最相关分块应排在最前");
        assertTrue(hits.stream().noneMatch(c -> c.userId().equals("u2")), "应严格按 userId 隔离");
        assertTrue(hits.stream().allMatch(c -> TEST_TENANT.equals(c.tenant())), "应严格按 tenant 隔离");
    }

    @Test
    void findRelevant_emptyQuery_returnsByRecency() {
        SharedStoreBackedDocumentChunkStore store = store();
        store.save(chunk("c1", "kb", "u1", "内容甲"));
        store.save(chunk("c2", "kb", "u1", "内容乙"));
        List<DocumentChunk> hits = store.findRelevant(TEST_TENANT, "kb", "u1", "", 5);
        assertEquals(2, hits.size());
    }

    @Test
    void delete_removesChunk() {
        SharedStoreBackedDocumentChunkStore store = store();
        store.save(chunk("c1", "kb", "u1", "待删除内容"));
        store.delete("c1");
        assertTrue(store.findAll(TEST_TENANT).isEmpty());
    }

    @Test
    void crossTenantChunksAreInvisible() {
        SharedStoreBackedDocumentChunkStore store = store();
        store.save(DocumentChunk.of("tenantA", "kb", "u1", "c1", "租户A的私有内容", null, 180));
        store.save(DocumentChunk.of("tenantB", "kb", "u1", "c1", "租户B的私有内容", null, 180));

        List<DocumentChunk> hitsA = store.findRelevant("tenantA", "kb", "u1", "内容", 5);
        assertTrue(hitsA.stream().allMatch(c -> "tenantA".equals(c.tenant())), "tenantA 查询只命中 tenantA");
        assertTrue(hitsA.stream().noneMatch(c -> "tenantB".equals(c.tenant())), "跨租户不可见");

        List<DocumentChunk> hitsB = store.findRelevant("tenantB", "kb", "u1", "内容", 5);
        assertTrue(hitsB.stream().allMatch(c -> "tenantB".equals(c.tenant())), "tenantB 查询只命中 tenantB");

        // 自然键含 tenant：不同租户的同（collection,userId,chunkId）得到不同存储主键，互不覆盖。
        DocumentChunk a = DocumentChunk.of("tenantA", "kb", "u1", "c1", "x", null, 180);
        DocumentChunk b = DocumentChunk.of("tenantB", "kb", "u1", "c1", "x", null, 180);
        assertNotEquals(a.id(), b.id(), "不同租户的分块主键应不同");
    }

    /** 测试用内存 BaseStore 实现（乐观并发由 putIfVersion 版本号保证）。 */
    private static final class InMemoryBaseStoreForTest implements BaseStore {
        private static final String SEP = "/";
        private final ConcurrentMap<String, StoreItem> storage = new ConcurrentHashMap<>();

        private static String fullKey(List<String> segments, String key) {
            return String.join(SEP, segments) + SEP + key;
        }

        @Override
        public StoreItem get(List<String> segments, String key) {
            return storage.get(fullKey(segments, key));
        }

        @Override
        public void put(List<String> segments, String key, Map<String, Object> value) {
            String fk = fullKey(segments, key);
            storage.compute(fk, (k, v) -> new StoreItem(key, value, v == null ? 1L : v.version() + 1));
        }

        @Override
        public boolean putIfVersion(List<String> segments, String key, Map<String, Object> value, long expectedVersion) {
            String fk = fullKey(segments, key);
            StoreItem current = storage.get(fk);
            long currentVersion = current == null ? 0L : current.version();
            if (currentVersion != expectedVersion) {
                return false;
            }
            storage.put(fk, new StoreItem(key, value, currentVersion + 1));
            return true;
        }

        @Override
        public List<StoreItem> search(List<String> segments, int offset, int limit) {
            String prefix = String.join(SEP, segments) + SEP;
            List<StoreItem> matched = storage.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix))
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());
            int from = Math.max(0, offset);
            if (from >= matched.size()) {
                return List.of();
            }
            int to = limit <= 0 ? matched.size() : Math.min(from + limit, matched.size());
            return List.copyOf(matched.subList(from, to));
        }

        @Override
        public void delete(List<String> segments, String key) {
            storage.remove(fullKey(segments, key));
        }
    }
}
