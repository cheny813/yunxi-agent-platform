package io.yunxi.fabric;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 平台织面。框架 {@link BaseStore} 接口的进程内内存实现（单机 / 演示 / 测试用）。
 *
 * <p>RAG 分块存储以框架共享存储抽象 {@code BaseStore} 为唯一后端：分布式部署走框架 Redis 实现，
 * 单机部署走本内存实现——两者共用同一抽象，使 RAG 检索平面在任意部署形态下都可存续，
 * 不引入第二份分块存储真相源。</p>
 *
 * <p>乐观并发由 {@code putIfVersion} 的版本号保证：首次写入期望版本 0，后续期望当前版本。</p>
 */
public class InMemoryBaseStore implements BaseStore {

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
            return new ArrayList<>();
        }
        int to = limit <= 0 ? matched.size() : Math.min(from + limit, matched.size());
        return new ArrayList<>(matched.subList(from, to));
    }

    @Override
    public void delete(List<String> segments, String key) {
        storage.remove(fullKey(segments, key));
    }
}
