package io.yunxi.platform.framework.sync;

import lombok.Getter;

/**
 * 同步閿佸紓甯? *
 */
@Getter
public class SyncLockException extends RuntimeException {

    private final String lockKey;
    private final String syncType;
    private final String targetId;

    public SyncLockException(String message) {
        super(message);
        this.lockKey = null;
        this.syncType = null;
        this.targetId = null;
    }

    public SyncLockException(String message, String lockKey) {
        super(message);
        this.lockKey = lockKey;
        this.syncType = null;
        this.targetId = null;
    }

    public SyncLockException(String message, String syncType, String targetId) {
        super(message);
        this.lockKey = buildLockKey(syncType, targetId);
        this.syncType = syncType;
        this.targetId = targetId;
    }

    public SyncLockException(String message, Throwable cause) {
        super(message, cause);
        this.lockKey = null;
        this.syncType = null;
        this.targetId = null;
    }

    private static String buildLockKey(String syncType, String targetId) {
        return "sync:lock:" + syncType + ":" + targetId;
    }
}
