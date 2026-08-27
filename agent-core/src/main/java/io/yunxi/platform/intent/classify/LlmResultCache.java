package io.yunxi.platform.intent.classify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.yunxi.platform.intent.Intent;

/**
 * LLM 意图分类结果缓存。
 *
 * <p>key = {@code domain|normalizedQuery|whitelistVersion}——查询改写、领域规则版本任一变化
 * 即自动失效。TTL 惰性过期（get 时校验，不设后台任务）；max-size 超限时抽样清理
 * （近似热度淘汰，避免全局遍历）。命中计数累积热度，供观测端点展示 TopN。</p>
 *
 * <p>本类非 Spring Bean，由 {@link HybridIntentClassifier} 组合持有。</p>
 */
public class LlmResultCache {

    private static final Logger log = LoggerFactory.getLogger(LlmResultCache.class);

    /** 缓存条目：意图 + 写入时间 + 命中次数（热度） */
    private static final class Entry {
        final Intent intent;
        final long createdAt;
        long hitCount;

        Entry(Intent intent, long now) {
            this.intent = intent;
            this.createdAt = now;
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxSize;

    public LlmResultCache(Duration ttl, int maxSize) {
        this.ttlMillis = ttl.toMillis();
        this.maxSize = Math.max(1, maxSize);
    }

    /**
     * 构造缓存 key：domain + 归一化 query + 白名单版本号。
     *
     * <p>归一化 = trim + 小写 + 空白折叠，保证同一查询的不同写法命中同一缓存条目。</p>
     */
    public static String key(String domain, String query, long version) {
        return domain + "|" + normalize(query) + "|" + version;
    }

    private static String normalize(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /** 命中返回缓存的 Intent；未命中/过期返回 null */
    public Intent get(String key) {
        Entry e = store.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.createdAt > ttlMillis) {
            store.remove(key, e);
            return null;
        }
        e.hitCount++;
        return e.intent;
    }

    public void put(String key, Intent intent) {
        if (store.size() >= maxSize) {
            evict();
        }
        store.put(key, new Entry(intent, System.currentTimeMillis()));
    }

    /** max-size 超限时抽样清理：取固定窗口，按命中数淘汰其中 1/4（近似热度淘汰） */
    private void evict() {
        int n = Math.min(64, store.size());
        List<Map.Entry<String, Entry>> samples = new ArrayList<>(n);
        for (Map.Entry<String, Entry> e : store.entrySet()) {
            if (samples.size() >= n) {
                break;
            }
            samples.add(e);
        }
        samples.sort(Comparator.comparingLong(s -> s.getValue().hitCount));
        int removeCount = Math.max(1, samples.size() / 4);
        for (int i = 0; i < removeCount && i < samples.size(); i++) {
            store.remove(samples.get(i).getKey(), samples.get(i).getValue());
        }
        log.debug("LlmResultCache 超限清理 {} 条（size={}）", removeCount, store.size());
    }

    /**
     * 热度 TopN（供 {@code /actuator/intent/suggest-words} 观测端点使用）。
     *
     * <p>domain 可空 = 全域热度；非空时按缓存 key 前缀 {@code domain|} 过滤
     * （key = domain|query|version，见 {@link #key}）。条目数不足 N 时全量返回。</p>
     */
    public List<Intent> hotEntries(String domain, int topN) {
        String prefix = (domain == null || domain.isBlank()) ? null : domain + "|";
        return store.entrySet().stream()
                .filter(e -> prefix == null || e.getKey().startsWith(prefix))
                .sorted(Comparator.comparingLong(e -> -e.getValue().hitCount))
                .limit(topN)
                .map(e -> e.getValue().intent)
                .toList();
    }
}
