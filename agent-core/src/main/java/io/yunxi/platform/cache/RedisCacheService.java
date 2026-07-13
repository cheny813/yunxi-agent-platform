package io.yunxi.platform.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.spi.cache.CacheProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Redis 缓存服务
 * <p>提供统一的 Redis 缓存操作接口，支持对象序列化/反序列化（JSON）、TTL 过期、批量操作、Hash 结构存储。</p>
 * <p>实现 {@link CacheProvider} SPI 接口，允许上层通过接口访问。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
public class RedisCacheService implements CacheProvider {

    /** Redis 模板 */
    private final StringRedisTemplate redisTemplate;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;
    /** 缓存键前缀 */
    private static final String CACHE_PREFIX = "yunxi:cache:";
    /** 默认过期时间 */
    private static final long DEFAULT_TTL_HOURS = 24;

    /**
     * 构造 Redis 缓存服务。
     *
     * @param redisTemplate Redis 模板（由 Spring 注入）
     * @param objectMapper  JSON 序列化器（由 Spring 注入）
     */
    public RedisCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 构建完整缓存键 */
    private String buildKey(String namespace, String key) {
        return CACHE_PREFIX + namespace + ":" + key;
    }

    /** 存储对象（JSON 序列化，默认 TTL） */
    public <T> void put(String namespace, String key, T value) {
        put(namespace, key, value, Duration.ofHours(DEFAULT_TTL_HOURS));
    }

    /** 存储对象（带过期时间） */
    public <T> void put(String namespace, String key, T value, Duration ttl) {
        if (key == null || value == null) return;
        String fullKey = buildKey(namespace, key);
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(fullKey, json, ttl);
            log.debug("Redis 缓存写入: key={}", fullKey);
        } catch (JsonProcessingException e) {
            log.error("Redis 缓存序列化失败: key={}", fullKey, e);
        }
    }

    /** 获取对象（简单类型） */
    public <T> Optional<T> get(String namespace, String key, Class<T> type) {
        if (key == null) return Optional.empty();
        String fullKey = buildKey(namespace, key);
        try {
            String json = redisTemplate.opsForValue().get(fullKey);
            return json == null ? Optional.empty() : Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.error("Redis 缓存反序列化失败: key={}", fullKey, e);
            return Optional.empty();
        }
    }

    /** 获取对象（复杂类型，如 List<T>） */
    public <T> Optional<T> get(String namespace, String key, TypeReference<T> typeRef) {
        if (key == null) return Optional.empty();
        String fullKey = buildKey(namespace, key);
        try {
            String json = redisTemplate.opsForValue().get(fullKey);
            return json == null ? Optional.empty() : Optional.ofNullable(objectMapper.readValue(json, typeRef));
        } catch (JsonProcessingException e) {
            log.error("Redis 缓存反序列化失败: key={}", fullKey, e);
            return Optional.empty();
        }
    }

    /** 删除缓存 */
    public boolean delete(String namespace, String key) {
        if (key == null) return false;
        return Boolean.TRUE.equals(redisTemplate.delete(buildKey(namespace, key)));
    }

    /** 检查缓存是否存在 */
    public boolean exists(String namespace, String key) {
        if (key == null) return false;
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(namespace, key)));
    }

    /** 设置过期时间 */
    public boolean expire(String namespace, String key, Duration ttl) {
        if (key == null) return false;
        return Boolean.TRUE.equals(redisTemplate.expire(buildKey(namespace, key), ttl));
    }

    // ========== Hash 操作 ==========

    /** 存储到 Hash */
    public <T> void hPut(String namespace, String hashKey, String field, T value) {
        if (hashKey == null || field == null || value == null) return;
        String fullKey = buildKey(namespace, hashKey);
        try {
            redisTemplate.opsForHash().put(fullKey, field, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            log.error("Redis Hash 序列化失败", e);
        }
    }

    /** 从 Hash 获取 */
    public <T> Optional<T> hGet(String namespace, String hashKey, String field, Class<T> type) {
        if (hashKey == null || field == null) return Optional.empty();
        try {
            Object json = redisTemplate.opsForHash().get(buildKey(namespace, hashKey), field);
            return json == null ? Optional.empty() : Optional.ofNullable(objectMapper.readValue(json.toString(), type));
        } catch (Exception e) {
            log.error("Redis Hash 反序列化失败", e);
            return Optional.empty();
        }
    }

    /** 获取 Hash 所有字段 */
    public <T> Map<String, T> hGetAll(String namespace, String hashKey, Class<T> type) {
        if (hashKey == null) return Collections.emptyMap();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(buildKey(namespace, hashKey));
        Map<String, T> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                result.put(entry.getKey().toString(), objectMapper.readValue(entry.getValue().toString(), type));
            } catch (Exception e) {
                log.warn("Hash 字段反序列化失败: {}", entry.getKey());
            }
        }
        return result;
    }

    /** 删除 Hash 字段 */
    public long hDelete(String namespace, String hashKey, String... fields) {
        if (hashKey == null || fields == null || fields.length == 0) return 0;
        Long deleted = redisTemplate.opsForHash().delete(buildKey(namespace, hashKey), (Object[]) fields);
        return deleted != null ? deleted : 0;
    }

    /** 检查 Hash 字段是否存在 */
    public boolean hExists(String namespace, String hashKey, String field) {
        if (hashKey == null || field == null) return false;
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(buildKey(namespace, hashKey), field));
    }

    // ========== 列表操作 ==========

    /** 追加到列表右侧 */
    public <T> void lPush(String namespace, String key, T value) {
        if (key == null || value == null) return;
        try { redisTemplate.opsForList().rightPush(buildKey(namespace, key), objectMapper.writeValueAsString(value)); }
        catch (JsonProcessingException e) { log.error("Redis List 序列化失败", e); }
    }

    /** 获取列表所有元素 */
    public <T> List<T> lGetAll(String namespace, String key, Class<T> type) {
        if (key == null) return Collections.emptyList();
        List<String> jsonList = redisTemplate.opsForList().range(buildKey(namespace, key), 0, -1);
        if (jsonList == null || jsonList.isEmpty()) return Collections.emptyList();
        List<T> result = new ArrayList<>();
        for (String json : jsonList) {
            try { result.add(objectMapper.readValue(json, type)); }
            catch (Exception e) { log.warn("List 元素反序列化失败"); }
        }
        return result;
    }

    /** 裁剪列表，只保留最近 N 个元素 */
    public void lTrim(String namespace, String key, long size) {
        if (key == null || size <= 0) return;
        String fullKey = buildKey(namespace, key);
        Long listSize = redisTemplate.opsForList().size(fullKey);
        if (listSize != null && listSize > size) {
            redisTemplate.opsForList().trim(fullKey, -(size), -1);
        }
    }

    // ========== 批量操作 ==========

    /** 批量删除命名空间下所有键 */
    public long deleteByNamespace(String namespace) {
        Set<String> keys = redisTemplate.keys(CACHE_PREFIX + namespace + ":*");
        if (keys == null || keys.isEmpty()) return 0;
        Long count = redisTemplate.delete(keys);
        log.info("Redis 批量删除: namespace={}, count={}", namespace, count);
        return count != null ? count : 0;
    }

    /** 获取命名空间下所有键 */
    public Set<String> keys(String namespace) {
        Set<String> keys = redisTemplate.keys(CACHE_PREFIX + namespace + ":*");
        return keys != null ? keys : Collections.emptySet();
    }
}
