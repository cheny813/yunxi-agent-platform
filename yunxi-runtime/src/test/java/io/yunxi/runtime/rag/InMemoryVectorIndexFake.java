package io.yunxi.runtime.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 测试替身：内存向量索引，余弦相似度排序，按 tenant+collection+userId 过滤。供 VectorBackedChunkStoreTest 使用。 */
final class InMemoryVectorIndexFake implements VectorIndex {

    private static final class Entry {
        final String tenant;
        final String collection;
        final String userId;
        final float[] vector;

        Entry(String tenant, String collection, String userId, float[] vector) {
            this.tenant = tenant;
            this.collection = collection;
            this.userId = userId;
            this.vector = vector;
        }
    }

    private final Map<String, Entry> data = new ConcurrentHashMap<>();

    @Override
    public void upsert(String id, String tenant, String collection, String userId, float[] vector) {
        data.put(id, new Entry(tenant, collection, userId, vector));
    }

    @Override
    public List<String> search(String tenant, String collection, String userId, float[] query, int topK) {
        List<String> result = new ArrayList<>();
        data.forEach((id, e) -> {
            if (e.tenant.equals(tenant) && e.collection.equals(collection) && e.userId.equals(userId)) {
                double sim = cosine(query, e.vector);
                result.add(id + "\u0000" + sim);
            }
        });
        result.sort((a, b) -> Double.compare(
                Double.parseDouble(b.substring(b.indexOf('\u0000') + 1)),
                Double.parseDouble(a.substring(a.indexOf('\u0000') + 1))));
        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, result.size()); i++) {
            top.add(result.get(i).substring(0, result.get(i).indexOf('\u0000')));
        }
        return top;
    }

    @Override
    public void delete(String id) {
        data.remove(id);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }
}
