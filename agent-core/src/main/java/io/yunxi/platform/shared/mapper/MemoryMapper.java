package io.yunxi.platform.shared.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 记忆存储Mapper接口
 * 提供PostgreSQL记忆存储的数据库操作
 *
 * @author yunxi-agent-platform
 */
public interface MemoryMapper {

    /**
     * 插入或更新数据（UPSERT）
     *
     * @param tableName 表名
     * @param key       键
     * @param value     值
     * @param metadata  元数据（JSON字符串）
     * @return 影响行数
     */
    int upsert(@Param("tableName") String tableName,
            @Param("key") String key,
            @Param("value") String value,
            @Param("metadata") String metadata);

    /**
     * 根据key查询
     *
     * @param tableName 表名
     * @param key       键
     * @return 查询结果
     */
    Map<String, Object> selectByKey(@Param("tableName") String tableName,
            @Param("key") String key);

    /**
     * 根据key查询（包含元数据和时间戳）
     *
     * @param tableName 表名
     * @param key       键
     * @return 查询结果（包含value, metadata, createdAt, updatedAt）
     */
    Map<String, Object> selectWithMetadata(@Param("tableName") String tableName,
            @Param("key") String key);

    /**
     * 根据key删除
     *
     * @param tableName 表名
     * @param key       键
     * @return 影响行数
     */
    int deleteByKey(@Param("tableName") String tableName,
            @Param("key") String key);

    /**
     * 检查key是否存在
     *
     * @param tableName 表名
     * @param key       键
     * @return 是否存在
     */
    boolean existsByKey(@Param("tableName") String tableName,
            @Param("key") String key);

    /**
     * 列出所有key
     *
     * @param tableName 表名
     * @param limit     限制数量
     * @return key列表
     */
    List<String> listKeys(@Param("tableName") String tableName,
            @Param("limit") int limit);

    /**
     * 清空表
     *
     * @param tableName 表名
     * @return 影响行数
     */
    int truncateTable(@Param("tableName") String tableName);

    /**
     * 健康检查
     *
     * @return 1表示正常
     */
    int healthCheck();
}
