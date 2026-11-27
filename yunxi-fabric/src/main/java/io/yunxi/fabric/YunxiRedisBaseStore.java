package io.yunxi.fabric;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 基于全局 {@link StringRedisTemplate}（全局 LettuceConnectionFactory）的 {@link BaseStore} 实现。
 *
 * <p>框架 {@code RedisStore} 仅提供 {@code UnifiedJedis} 构造器，本类改用全局 Lettuce 连接工厂。
 * 存储语义与框架 {@code RedisStore} 对齐：每项存为 Redis Hash（字段 {@code value}=JSON、
 * {@code version}=long），namespace 维护一个有序集合索引以支撑 {@link #search} 的字典序扫描。
 *
 * <p>并发：{@link #put} 与 {@link #putIfVersion} 均通过 Lua {@code EVAL} 单命令原子执行（版本读 + 写 +
 * 索引更新一体），使跨进程共享同一 Redis 时 {@link #putIfVersion} 可安全作为分布式 CAS 原语。</p>
 */
public class YunxiRedisBaseStore implements BaseStore {

    /** 默认 Redis 键前缀；与框架 RedisStore 区分，避免键空间冲突。 */
    public static final String DEFAULT_KEY_PREFIX = "yunxi:store:";

    private static final String NS_SEPARATOR = "\0";

    private static final RedisScript<String> PUT_SCRIPT =
            RedisScript.of(
                    "local v = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') + 1 "
                            + "redis.call('HSET', KEYS[1], 'value', ARGV[1]) "
                            + "redis.call('HSET', KEYS[1], 'version', tostring(v)) "
                            + "redis.call('ZADD', KEYS[2], 0, ARGV[2]) "
                            + "return tostring(v)",
                    String.class);

    private static final RedisScript<String> PUT_IF_VERSION_SCRIPT =
            RedisScript.of(
                    "local cur = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') "
                            + "if cur ~= tonumber(ARGV[3]) then return '0' end "
                            + "local v = cur + 1 "
                            + "redis.call('HSET', KEYS[1], 'value', ARGV[1]) "
                            + "redis.call('HSET', KEYS[1], 'version', tostring(v)) "
                            + "redis.call('ZADD', KEYS[2], 0, ARGV[2]) "
                            + "return tostring(v)",
                    String.class);

    private static final RedisScript<Long> DELETE_SCRIPT =
            RedisScript.of(
                    "redis.call('DEL', KEYS[1]) redis.call('ZREM', KEYS[2], ARGV[1]) return 1", Long.class);

    private final StringRedisTemplate template;
    private final String keyPrefix;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public YunxiRedisBaseStore(StringRedisTemplate template, String keyPrefix) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        validateKey(key);
        String itemKey = itemKey(namespace, key);
        Map<Object, Object> hash = template.opsForHash().entries(itemKey);
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        return toItem(key, hash);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        validateKey(key);
        String itemKey = itemKey(namespace, key);
        String idxKey = indexKey(namespace);
        String json = serialize(value);
        template.execute(PUT_SCRIPT, List.of(itemKey, idxKey), json, key);
    }

    @Override
    public boolean supportsAtomicSessionStorage() {
        return true;
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        validateKey(key);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        String itemKey = itemKey(namespace, key);
        String idxKey = indexKey(namespace);
        String json = serialize(value);
        String result =
                template.execute(
                        PUT_IF_VERSION_SCRIPT,
                        List.of(itemKey, idxKey),
                        json,
                        key,
                        Long.toString(expectedVersion));
        return result != null && !"0".equals(result);
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        int safeOffset = Math.max(offset, 0);
        String idxKey = indexKey(namespace);
        Set<String> members = template.opsForZSet().rangeByLex(idxKey, Range.unbounded());
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(members);
        int to = Math.min(safeOffset + limit, keys.size());
        if (safeOffset >= keys.size()) {
            return List.of();
        }
        List<StoreItem> items = new ArrayList<>();
        for (String k : keys.subList(safeOffset, to)) {
            Map<Object, Object> hash = template.opsForHash().entries(itemKey(namespace, k));
            if (hash == null || hash.isEmpty()) {
                continue; // stale index entry — tolerated
            }
            items.add(toItem(k, hash));
        }
        return items;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        validateKey(key);
        String itemKey = itemKey(namespace, key);
        String idxKey = indexKey(namespace);
        template.execute(DELETE_SCRIPT, List.of(itemKey, idxKey), key);
    }

    // -------------------------------------------------------------------------
    //  Helpers（语义对齐框架 RedisStore，键布局独立以避免冲突）
    // -------------------------------------------------------------------------

    private StoreItem toItem(String key, Map<Object, Object> hash) {
        String json = (String) hash.get("value");
        String versionStr = (String) hash.get("version");
        long version = 0L;
        if (versionStr != null) {
            try {
                version = Long.parseLong(versionStr);
            } catch (NumberFormatException ignored) {
                // leave version as 0 — caller treats this as "unknown"
            }
        }
        Map<String, Object> value = deserialize(json);
        return new StoreItem(key, value, version);
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to JSON-encode store value", e);
        }
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to JSON-decode store value", e);
        }
    }

    private String itemKey(List<String> namespace, String key) {
        return keyPrefix + "item:" + namespacePath(namespace) + NS_SEPARATOR + key;
    }

    private String indexKey(List<String> namespace) {
        return keyPrefix + "idx:" + namespacePath(namespace);
    }

    private static String namespacePath(List<String> namespace) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < namespace.size(); i++) {
            String segment = namespace.get(i);
            if (segment == null) {
                throw new IllegalArgumentException("namespace segment must not be null");
            }
            if (i > 0) {
                sb.append(NS_SEPARATOR);
            }
            sb.append(segment);
        }
        return sb.toString();
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
        if (key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("key must not contain the NUL character");
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return DEFAULT_KEY_PREFIX;
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }
}
