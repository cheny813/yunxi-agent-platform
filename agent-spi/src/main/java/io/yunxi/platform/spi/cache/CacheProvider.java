package io.yunxi.platform.spi.cache;

import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 缓存服务 SPI 接口
 *
 * <p>提供统一的缓存操作接口，支持：
 * <ul>
 *   <li>对象序列化/反序列化（JSON）</li>
 *   <li>TTL 过期时间</li>
 *   <li>批量操作</li>
 *   <li>Hash 结构存储</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface CacheProvider {

    // ==================== 基础操作 ====================

    /**
     * 存储对象（JSON 序列化）
     *
     * @param namespace 命名空间
     * @param key       键
     * @param value     值对象
     * @param <T>       值类型
     */
    <T> void put(String namespace, String key, T value);

    /**
     * 存储对象（带过期时间）
     *
     * @param namespace 命名空间
     * @param key       键
     * @param value     值对象
     * @param ttl       过期时间
     * @param <T>       值类型
     */
    <T> void put(String namespace, String key, T value, Duration ttl);

    /**
     * 获取对象
     *
     * @param namespace 命名空间
     * @param key       键
     * @param type      值类型
     * @param <T>       值类型
     * @return 值对象（可能为空）
     */
    <T> Optional<T> get(String namespace, String key, Class<T> type);

    /**
     * 获取对象（复杂类型）
     *
     * @param namespace 命名空间
     * @param key       键
     * @param typeRef   类型引用
     * @param <T>       值类型
     * @return 值对象（可能为空）
     */
    <T> Optional<T> get(String namespace, String key, TypeReference<T> typeRef);

    /**
     * 删除缓存
     *
     * @param namespace 命名空间
     * @param key       键
     * @return 是否删除成功
     */
    boolean delete(String namespace, String key);

    /**
     * 检查缓存是否存在
     *
     * @param namespace 命名空间
     * @param key       键
     * @return 是否存在
     */
    boolean exists(String namespace, String key);

    /**
     * 设置过期时间
     *
     * @param namespace 命名空间
     * @param key       键
     * @param ttl       过期时间
     * @return 是否设置成功
     */
    boolean expire(String namespace, String key, Duration ttl);

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
    <T> void hPut(String namespace, String hashKey, String field, T value);

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
    <T> Optional<T> hGet(String namespace, String hashKey, String field, Class<T> type);

    /**
     * 获取 Hash 的所有字段
     *
     * @param namespace 命名空间
     * @param hashKey   Hash 键
     * @param type      值类型
     * @param <T>       值类型
     * @return 字段名到值的映射
     */
    <T> Map<String, T> hGetAll(String namespace, String hashKey, Class<T> type);

    /**
     * 删除 Hash 字段
     *
     * @param namespace 命名空间
     * @param hashKey   Hash 键
     * @param fields    字段名列表
     * @return 删除的字段数量
     */
    long hDelete(String namespace, String hashKey, String... fields);

    /**
     * 检查 Hash 字段是否存在
     *
     * @param namespace 命名空间
     * @param hashKey   Hash 键
     * @param field     字段名
     * @return 是否存在
     */
    boolean hExists(String namespace, String hashKey, String field);

    // ==================== 列表操作 ====================

    /**
     * 追加到列表右侧
     *
     * @param namespace 命名空间
     * @param key       键
     * @param value     值
     * @param <T>       值类型
     */
    <T> void lPush(String namespace, String key, T value);

    /**
     * 获取列表所有元素
     *
     * @param namespace 命名空间
     * @param key       键
     * @param type      值类型
     * @param <T>       值类型
     * @return 列表
     */
    <T> List<T> lGetAll(String namespace, String key, Class<T> type);

    /**
     * 裁剪列表，只保留最近的 N 个元素
     *
     * @param namespace 命名空间
     * @param key       键
     * @param size      保留数量
     */
    void lTrim(String namespace, String key, long size);

    // ==================== 批量操作 ====================

    /**
     * 批量删除命名空间下的所有键
     *
     * @param namespace 命名空间
     * @return 删除的数量
     */
    long deleteByNamespace(String namespace);

    /**
     * 获取命名空间下的所有键
     *
     * @param namespace 命名空间
     * @return 键集合
     */
    Set<String> keys(String namespace);
}
