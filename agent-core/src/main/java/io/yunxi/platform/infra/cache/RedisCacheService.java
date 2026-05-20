package io.yunxi.platform.infra.cache;

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
 * 
 * <p>
 * 提供统一的 Redis 缓存操作接口，支持：
 * <ul>
 * <li>对象序列化/反序列化（JSON）</li>
 * <li>TTL 过期时间</li>
 * <li>批量操作</li>
 * <li>Hash 结构存储</li>
 * </ul>
 * </p>
 * 
 * <p>
 * <b>集群部署支持</b>：所有缓存数据存储在 Redis 中，多节点共享同一份数据
 * </p>
 * 
 * <p>
 * <b>架构分层</b>：实现 {@link CacheProvider} SPI 接口，允许上层通过接口访问
 * </p>
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

    /** 默认过期时间（小时） */
    private static final long DEFAULT_TTL_HOURS = 24;

    /**
     * 构造函数
     *
     * @param redisTemplate Redis 模板
     * @param objectMapper  JSON 序列化工具
     */
    public RedisCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 基础操作 ====================

    /**
     * 构建完整的缓存键
     */
    private String buildKey(String namespace, String key) {
        return CACHE_PREFIX + namespace + ":" + key;
    }

    /**
     * 存储对象（JSON 序列化）
     *
     * @param namespace 命名空间
     * @param key       键
     * @param value     值对象
     * @param <T>       值类型
     */
    public <T> void put(String namespace, String key, T value) {
        put(namespace, key, value, Duration.ofHours(DEFAULT_TTL_HOURS));
    }

    /**
     * 存储对象（带过期时间）
     *
     * @param namespace 命名空间
     * @param key       键
     * @param value     值对象
     * @param ttl       过期时间
     * @param <T>       值类型
     */
    public <T> void put(String namespace, String key, T value, Duration ttl) {
        if (key == null || value == null) {
            return;
        }
        String fullKey = buildKey(namespace, key);
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(fullKey, json, ttl);
            log.debug("Redis 缓存写入: key={}, ttl={}", fullKey, ttl);
        } catch (JsonProcessingException e) {
            log.error("Redis 缓存序列化失败: key={}, error={}", fullKey, e.getMessage());
        }
    }

    /**
     * 获取对象
     *
     * @param namespace 命名空间
     * @param key       键
     * @param type      值类型
     * @param <T>       值类型
     * @return 值对象（可能为空）
     */
    public <T> Optional<T> get(String namespace, String key, Class<T> type) {
        if (key == null) {
            return Optional.empty();
        }
        String fullKey = buildKey(namespace, key);
        try {
            String json = redisTemplate.opsForValue().get(fullKey);
            if (json == null) {
                return Optional.empty();
            }
            T value = objectMapper.readValue(json, type);
            log.debug("Redis 缓存命中: key={}", fullKey);
            return Optional.ofNullable(value);
        } catch (JsonProcessingException e) {
            log.error("Redis 缓存反序列化失败: key={}, error={}", fullKey, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 获取对象（复杂类型）
     *
     * @param namespace 命名空间
     * @param key       键
     * @param typeRef   类型引用
     * @param <T>       值类型
     * @return 值对象（可能为空）
     */
    public <T> Optional<T> get(String namespace, String key, TypeReference<T> typeRef) {
        if (key == null) {
            return Optional.empty();
        }
        String fullKey = buildKey(namespace, key);
        try {
            String json = redisTemplate.opsForValue().get(fullKey);
            if (json == null) {
                return Optional.empty();
            }
            T value = objectMapper.readValue(json, typeRef);
            log.debug("Redis 缓存命中: key={}", fullKey);
            return Optional.ofNullable(value);
        } catch (JsonProcessingException e) {
            log.error("Redis 缓存反序列化失败: key={}, error={}", fullKey, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 删除缓存
     *
     * @param namespace 命名空间
     * @param key       键
     * @return 是否删除成功
     */
    public boolean delete(String namespace, String key) {
        if (key == null) {
            return false;
        }
        String fullKey = buildKey(namespace, key);
        Boolean deleted = redisTemplate.delete(fullKey);
        log.debug("Redis 缓存删除: key={}, success={}", fullKey, deleted);
        return Boolean.TRUE.equals(deleted);
    }

    /**
     * 检查缓存是否存在
     *
     * @param namespace 命名空间
     * @param key       键
     * @return 是否存在
     */
    public boolean exists(String namespace, String key) {
        if (key == null) {
            return false;
        }
        String fullKey = buildKey(namespace, key);
        Boolean exists = redisTemplate.hasKey(fullKey);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 设置过期时间
     *
     * @param namespace 命名空间
     * @param key       键
     * @param ttl       过期时间
     * @return 是否设置成功
     */
    public boolean expire(String namespace, String key, Duration ttl) {
        if (key == null) {
            return false;
        }
        String fullKey = buildKey(namespace, key);
        Boolean result = redisTemplate.expire(fullKey, ttl);
        return Boolean.TRUE.equals(result);
    }

    // ==================== Hash 操作 ====================

    /**
     * 存储到 Hash
     *
     * @param namespace 命名空间
     * @param hashKey   Hash 键
     * @param field     字段名
     * @param value     值
     * @param <T>       值类型
     */
    public <T> void hPut(String namespace, String hashKey, String field, T value) {
        if (hashKey == null || field == null || value == null) {
            return;
        }
        String fullKey = buildKey(namespace, hashKey);
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForHash().put(fullKey, field, json);
            log.debug("Redis Hash 写入: key={}, field={}", fullKey, field);
        } catch (JsonProcessingException e) {
            log.error("Redis Hash 序列化失败: key={}, field={}, error={}", fullKey, field, e.getMessage());
        }
    }

    /**
     * 从 Hash 获取
     *
     * @param namespace 命名空间
     * @param hashKey   Hash 键
     * @param field     字段名
     * @param type      值类型
     * @param <T>       值类型
     * @return 值对象（可能为空）
     */
    public <T> Optional<T> hGet(String namespace, String hashKey, String field, Class<T> type) {
        if (hashKey == null || field == null) {
            return Optional.empty();
        }
        String fullKey = buildKey(namespace, hashKey);
        try {
            Object json = redisTemplate.opsForHash().get(fullKey, field);
            if (json == null) {
                return Optional.empty();
            }
            T value = objectMapper.readValue(json.toString(), type);
            log.debug("Redis Hash 命中: key={}, field={}", fullKey, field);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.error("Redis Hash 反序列化失败: key={}, field={}, error={}", fullKey, field, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 获取 Hash 的所有字段
     *
     * @param namespace 命名空间
     * @param hashKey   Hash 键
     * @param type      值类型
     * @param <T>       值类型
     * @return 字段名到值的映射
     */
    public <T> Map<String, T> hGetAll(String namespace, String hashKey, Class<T> type) {
        if (hashKey == null) {
            return Collections.emptyMap();
        }
        String fullKey = buildKey(namespace, hashKey);
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(fullKey);
            Map<String, T> result = new HashMap<>();
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                try {
                    T value = objectMapper.readValue(entry.getValue().toString(), type);
                    result.put(entry.getKey().toString(), value);
                } catch (Exception e) {
                    log.warn("Redis Hash 字段反序列化失败: field={}", entry.getKey());
                }
            }
            log.debug("Redis Hash 获取全部: key={}, size={}", fullKey, result.size());
            return result;
        } catch (Exception e) {
            log.error("Redis Hash 获取失败: key={}, error={}", fullKey, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 删除 Hash 字段
     *
     * @param namespace 命名空间
     * @param hashKey   Hash 键
     * @param fields    字段名列表
     * @return 删除的字段数量
     */
    public long hDelete(String namespace, String hashKey, String... fields) {
        if (hashKey == null || fields == null || fields.length == 0) {
            return 0;
        }
        String fullKey = buildKey(namespace, hashKey);
        Long deleted = redisTemplate.opsForHash().delete(fullKey, (Object[]) fields);
        log.debug("Redis Hash 删除: key={}, fields={}, count={}", fullKey, fields, deleted);
        return deleted != null ? deleted : 0;
    }

    /**
     * 检查 Hash 字段是否存在
     *
     * @param namespace 命名空间
     * @param hashKey   Hash 键
     * @param field     字段名
     * @return 是否存在
     */
    public boolean hExists(String namespace, String hashKey, String field) {
        if (hashKey == null || field == null) {
            return false;
        }
        String fullKey = buildKey(namespace, hashKey);
        Boolean exists = redisTemplate.opsForHash().hasKey(fullKey, field);
        return Boolean.TRUE.equals(exists);
    }

    // ==================== 列表操作 ====================

    /**
     * 追加到列表右侧
     *
     * @param namespace 命名空间
     * @param key       键
     * @param value     值
     * @param <T>       值类型
     */
    public <T> void lPush(String namespace, String key, T value) {
        if (key == null || value == null) {
            return;
        }
        String fullKey = buildKey(namespace, key);
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForList().rightPush(fullKey, json);
            log.debug("Redis List 追加: key={}", fullKey);
        } catch (JsonProcessingException e) {
            log.error("Redis List 序列化失败: key={}, error={}", fullKey, e.getMessage());
        }
    }

    /**
     * 获取列表所有元素
     *
     * @param namespace 命名空间
     * @param key       键
     * @param type      值类型
     * @param <T>       值类型
     * @return 列表
     */
    public <T> List<T> lGetAll(String namespace, String key, Class<T> type) {
        if (key == null) {
            return Collections.emptyList();
        }
        String fullKey = buildKey(namespace, key);
        try {
            List<String> jsonList = redisTemplate.opsForList().range(fullKey, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) {
                return Collections.emptyList();
            }
            List<T> result = new ArrayList<>();
            for (String json : jsonList) {
                try {
                    result.add(objectMapper.readValue(json, type));
                } catch (Exception e) {
                    log.warn("Redis List 元素反序列化失败");
                }
            }
            log.debug("Redis List 获取全部: key={}, size={}", fullKey, result.size());
            return result;
        } catch (Exception e) {
            log.error("Redis List 获取失败: key={}, error={}", fullKey, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 裁剪列表，只保留最近的 N 个元素
     *
     * @param namespace 命名空间
     * @param key       键
     * @param size      保留数量
     */
    public void lTrim(String namespace, String key, long size) {
        if (key == null || size <= 0) {
            return;
        }
        String fullKey = buildKey(namespace, key);
        Long listSize = redisTemplate.opsForList().size(fullKey);
        if (listSize != null && listSize > size) {
            redisTemplate.opsForList().trim(fullKey, -(size), -1);
            log.debug("Redis List 裁剪: key={}, from={} to={}", fullKey, listSize, size);
        }
    }

    // ==================== 批量操作 ====================

    /**
     * 批量删除命名空间下的所有键
     *
     * @param namespace 命名空间
     * @return 删除的数量
     */
    public long deleteByNamespace(String namespace) {
        String pattern = CACHE_PREFIX + namespace + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long count = redisTemplate.delete(keys);
        log.info("Redis 批量删除: namespace={}, count={}", namespace, count);
        return count != null ? count : 0;
    }

    /**
     * 获取命名空间下的所有键
     *
     * @param namespace 命名空间
     * @return 键集合
     */
    public Set<String> keys(String namespace) {
        String pattern = CACHE_PREFIX + namespace + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        return keys != null ? keys : Collections.emptySet();
    }
}
