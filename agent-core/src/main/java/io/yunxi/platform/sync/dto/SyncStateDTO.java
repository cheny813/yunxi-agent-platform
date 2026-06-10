package io.yunxi.platform.sync.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步状态数据传输对象
 */
@Data
public class SyncStateDTO {

    /** 最后同步时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastSyncTime;

    /** 最后同步增量版本 */
    private String lastSyncVersion;

    /** 同步状态: IDLE/RUNNING/SUCCESS/FAILED */
    private String status;

    /** 同步记录数 */
    private long recordCount;

    /** 最后错误信息 */
    private String lastError;

    /** 同步耗时（毫秒） */
    private long durationMs;

    /**
     * 创建空闲状态
     */
    public static SyncStateDTO idle() {
        SyncStateDTO dto = new SyncStateDTO();
        dto.setStatus("IDLE");
        return dto;
    }

    /**
     * 创建运行中状态
     */
    public static SyncStateDTO running() {
        SyncStateDTO dto = new SyncStateDTO();
        dto.setStatus("RUNNING");
        dto.setLastSyncTime(LocalDateTime.now());
        return dto;
    }

    /**
     * 创建成功状态
     */
    public static SyncStateDTO success(long recordCount, long durationMs) {
        SyncStateDTO dto = new SyncStateDTO();
        dto.setStatus("SUCCESS");
        dto.setLastSyncTime(LocalDateTime.now());
        dto.setRecordCount(recordCount);
        dto.setDurationMs(durationMs);
        return dto;
    }

    /**
     * 创建失败状态
     */
    public static SyncStateDTO failed(String lastError) {
        SyncStateDTO dto = new SyncStateDTO();
        dto.setStatus("FAILED");
        dto.setLastSyncTime(LocalDateTime.now());
        dto.setLastError(lastError);
        return dto;
    }
}
