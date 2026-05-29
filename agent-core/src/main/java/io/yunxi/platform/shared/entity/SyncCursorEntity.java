package io.yunxi.platform.shared.entity;

import java.time.LocalDateTime;

/**
 * 同步游标实体
 * <p>
 * 记录每条同步管道的游标值（如增量同步的 update_time），重启不丢失。
 * </p>
 */
public class SyncCursorEntity {

    /** 管道名称（唯一标识） */
    private String pipelineName;

    /** 游标值（上次同步的最大 update_time） */
    private String cursorValue;

    /** 上次同步行数 */
    private Integer syncedRows;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    public String getPipelineName() { return pipelineName; }
    public void setPipelineName(String pipelineName) { this.pipelineName = pipelineName; }

    public String getCursorValue() { return cursorValue; }
    public void setCursorValue(String cursorValue) { this.cursorValue = cursorValue; }

    public Integer getSyncedRows() { return syncedRows; }
    public void setSyncedRows(Integer syncedRows) { this.syncedRows = syncedRows; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}