package io.yunxi.platform.shared.mapper;

import io.yunxi.platform.shared.entity.SyncCursorEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 同步游标 Mapper
 * <p>
 * 提供同步游标的持久化读写，重启不丢失。
 * </p>
 */
@Mapper
public interface SyncCursorMapper {

    /**
     * 根据管道名称查询游标
     */
    SyncCursorEntity findByPipelineName(@Param("pipelineName") String pipelineName);

    /**
     * 插入或更新游标（MySQL UPSERT）
     */
    int upsert(@Param("pipelineName") String pipelineName,
               @Param("cursorValue") String cursorValue,
               @Param("syncedRows") Integer syncedRows);
}