package io.yunxi.platform.sync;

import lombok.Getter;

/**
 * 同步锁异常，当同一业务操作获取或持有分布式锁失败时抛出
 *
 * <p>支持两种锁标识方式：
 * <ul>
 *   <li>直接传入完整锁键（{@code lockKey}）</li>
 *   <li>传入同步类型（{@code syncType}）和目标ID（{@code targetId}），由内部自动拼接锁键</li>
 * </ul>
 *
 * <p>锁键格式为：{@code sync:lock:{syncType}:{targetId}}
 */
@Getter
public class SyncLockException extends RuntimeException {

    /** 完整锁键，格式 sync:lock:{syncType}:{targetId} */
    private final String lockKey;

    /** 同步任务类型，如数据同步的业务分类 */
    private final String syncType;

    /** 同步目标标识，如业务对象的主键ID */
    private final String targetId;

    /**
     * 仅携带异常消息和锁信息为空
     *
     * @param message 异常描述
     */
    public SyncLockException(String message) {
        super(message);
        this.lockKey = null;
        this.syncType = null;
        this.targetId = null;
    }

    /**
     * 携带异常消息和完整锁键
     *
     * @param message 异常描述
     * @param lockKey 完整锁键
     */
    public SyncLockException(String message, String lockKey) {
        super(message);
        this.lockKey = lockKey;
        this.syncType = null;
        this.targetId = null;
    }

    /**
     * 携带异常消息、同步类型和目标标识，由内部拼接锁键
     *
     * @param message  异常描述
     * @param syncType 同步任务类型
     * @param targetId 同步目标标识
     */
    public SyncLockException(String message, String syncType, String targetId) {
        super(message);
        this.lockKey = buildLockKey(syncType, targetId);
        this.syncType = syncType;
        this.targetId = targetId;
    }

    /**
     * 携带异常消息和原始异常原因
     *
     * @param message 异常描述
     * @param cause   原始异常原因
     */
    public SyncLockException(String message, Throwable cause) {
        super(message, cause);
        this.lockKey = null;
        this.syncType = null;
        this.targetId = null;
    }

    /**
     * 根据同步类型和目标标识拼接锁键
     *
     * @param syncType 同步任务类型
     * @param targetId 同步目标标识
     * @return 格式为{@code sync:lock:{syncType}:{targetId}} 的完整锁键
     */
    private static String buildLockKey(String syncType, String targetId) {
        return "sync:lock:" + syncType + ":" + targetId;
    }
}
