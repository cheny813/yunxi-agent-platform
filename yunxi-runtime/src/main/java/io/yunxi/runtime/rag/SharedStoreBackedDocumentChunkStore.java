package io.yunxi.runtime.rag;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RAG 文档分块存储：后端迁移到框架共享存储抽象 {@link BaseStore}（与长期记忆 MEMORY.md 同源）。
 *
 * <p>本实现以 {@code BaseStore} 为唯一后端（替代早期借用长期记忆存储承载分块的做法）；长期记忆下沉框架后，
 * 分布式部署走框架 Redis 实现，单机走内存实现，
 * 共用同一存储抽象，不引入第二份分块存储真相源。</p>
 *
 * <p>检索为轻量「关键词命中率 × 时间衰减」融合打分（与原非向量行为一致：原实现以 null embedding 入参，
 * 实际依赖关键词 / 元数据融合而非稠密向量）。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.rag.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(BaseStore.class)
@ConditionalOnMissingBean(DocumentChunkStore.class)
public class SharedStoreBackedDocumentChunkStore implements DocumentChunkStore {

    private static final List<String> NAMESPACE = List.of("yunxi-rag");
    private static final Pattern TOKEN = Pattern.compile("[\\w\\u4e00-\\u9fff]+");
    private static final double HALF_LIFE_HOURS = 24.0;

    private final BaseStore store;

    public SharedStoreBackedDocumentChunkStore(BaseStore store) {
        this.store = store;
    }

    @Override
    public void save(DocumentChunk chunk) {
        Map<String, Object> value = new java.util.HashMap<>();
        value.put("collection", chunk.collection());
        value.put("userId", chunk.userId());
        value.put("chunkId", chunk.chunkId());
        value.put("tenant", chunk.tenant());
        value.put("content", chunk.content());
        value.put("ttlDays", chunk.ttlDays());
        value.put("createdAt", chunk.createdAt().toEpochMilli());
        store.put(List.of("yunxi-rag", chunk.collection()), chunk.id(), value);
    }

    @Override
    public void delete(String chunkId) {
        for (StoreItem item : store.search(NAMESPACE, 0, Integer.MAX_VALUE)) {
            if (chunkId.equals(item.key())) {
                String collection = (String) item.value().get("collection");
                store.delete(List.of("yunxi-rag", collection), chunkId);
                return;
            }
        }
    }

    @Override
    public List<DocumentChunk> findAll(String tenant) {
        List<DocumentChunk> result = new ArrayList<>();
        for (StoreItem item : store.search(NAMESPACE, 0, Integer.MAX_VALUE)) {
            DocumentChunk c = toChunk(item);
            if (Objects.equals(c.tenant(), tenant)) {
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public List<DocumentChunk> findRelevant(String tenant, String collection, String userId, String query, int topK) {
        List<DocumentChunk> candidates = findAll(tenant).stream()
                .filter(c -> c.collection().equals(collection))
                .filter(c -> c.userId().equals(userId))
                .toList();
        Set<String> queryTokens = tokenize(query);
        List<Scored> scored = new ArrayList<>();
        for (DocumentChunk c : candidates) {
            double score = relevance(c, queryTokens);
            if (!queryTokens.isEmpty() && score <= 0.0) {
                continue;
            }
            scored.add(new Scored(c, score));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        int k = topK <= 0 ? scored.size() : Math.min(topK, scored.size());
        List<DocumentChunk> result = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            result.add(scored.get(i).chunk);
        }
        return result;
    }

    private double relevance(DocumentChunk chunk, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return timeDecay(chunk.createdAt());
        }
        Set<String> contentTokens = tokenize(chunk.content());
        int hit = 0;
        for (String t : queryTokens) {
            if (contentTokens.contains(t)) {
                hit++;
            }
        }
        if (hit == 0) {
            return 0.0;
        }
        double precision = (double) hit / queryTokens.size();
        return precision * timeDecay(chunk.createdAt());
    }

    private double timeDecay(Instant createdAt) {
        double ageHours = Duration.between(createdAt, Instant.now()).toHours();
        return Math.pow(0.5, ageHours / HALF_LIFE_HOURS);
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        java.util.regex.Matcher m = TOKEN.matcher(text.toLowerCase());
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    private static DocumentChunk toChunk(StoreItem item) {
        Map<String, Object> v = item.value();
        return new DocumentChunk(
                (String) v.get("tenant"),
                item.key(),
                (String) v.get("collection"),
                (String) v.get("userId"),
                (String) v.get("chunkId"),
                (String) v.get("content"),
                null,
                Instant.ofEpochMilli(((Number) v.get("createdAt")).longValue()),
                ((Number) v.get("ttlDays")).intValue());
    }

    private static final class Scored {
        final DocumentChunk chunk;
        final double score;

        Scored(DocumentChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
