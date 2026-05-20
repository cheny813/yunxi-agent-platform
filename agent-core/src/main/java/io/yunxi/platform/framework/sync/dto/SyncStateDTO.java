package io.yunxi.platform.framework.sync.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步鐘舵€佹暟鎹紶杈撳璞? *
 */
@Data
public class SyncStateDTO {

    /** 鏈€鍚庡悓姝ユ椂闂?*/
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastSyncTime;

    /** 鏈€鍚庡悓姝ョ増鏈?*/
    private String lastSyncVersion;

    /** 同步鐘舵€? IDLE/RUNNING/SUCCESS/FAILED */
    private String status;

    /** 同步记录鏁?*/
    private long recordCount;

    /** 鏈€鍚庨敊璇俊鎭?*/
    private String lastError;

    /** 同步鑰楁椂锛堟绉掞級 */
    private long durationMs;

    /**
     * 创建绌洪棽鐘舵€?     */
    public static SyncStateDTO idle() {
        SyncStateDTO dto = new SyncStateDTO();
        dto.setStatus("IDLE");
        return dto;
    }

    /**
     * 创建运行涓姸鎬?     */
    public static SyncStateDTO running() {
        SyncStateDTO dto = new SyncStateDTO();
        dto.setStatus("RUNNING");
        dto.setLastSyncTime(LocalDateTime.now());
        return dto;
    }

    /**
     * 创建成功鐘舵€?     */
    public static SyncStateDTO success(long recordCount, long durationMs) {
        SyncStateDTO dto = new SyncStateDTO();
        dto.setStatus("SUCCESS");
        dto.setLastSyncTime(LocalDateTime.now());
        dto.setRecordCount(recordCount);
        dto.setDurationMs(durationMs);
        return dto;
    }

    /**
     * 创建失败鐘舵€?     */
    public static SyncStateDTO failed(String lastError) {
        SyncStateDTO dto = new SyncStateDTO();
        dto.setStatus("FAILED");
        dto.setLastSyncTime(LocalDateTime.now());
        dto.setLastError(lastError);
        return dto;
    }
}
