package io.yunxi.platform.shared.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 记忆存储DDL执行器
 * 用于执行建表、建索引等DDL操作
 *
 * <p>
 * SQL语句配置在 resources/mapper/MemoryDdlMapper.xml 中
 * </p>
 *
 * @author yunxi-agent-platform
 */
public interface MemoryDdlMapper {

    /**
     * 创建表
     *
     * @param tableName 表名
     */
    void createTable(@Param("tableName") String tableName);

    /**
     * 创建key索引
     *
     * @param tableName 表名
     */
    void createKeyIndex(@Param("tableName") String tableName);

    /**
     * 创建created_at索引
     *
     * @param tableName 表名
     */
    void createCreatedAtIndex(@Param("tableName") String tableName);

    /**
     * 创建更新时间触发器函数
     */
    void createTriggerFunction();

    /**
     * 创建触发器
     *
     * @param tableName 表名
     */
    void createTrigger(@Param("tableName") String tableName);

    /**
     * 启用pgvector扩展
     */
    void enableVectorExtension();

    /**
     * 健康检查
     *
     * @return 1表示正常
     */
    int healthCheck();
}
