package io.yunxi.platform.framework.memory.exception;

/**
 * 记忆条目未找到异常
 * <p>
 * 当尝试访问或删除不存在的记忆条目时抛出
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class EntryNotFoundException extends RuntimeException {

    /**
     * 条目标识符
     */
    private final String entryId;

    /**
     * 目标存储（memory 或 user）
     */
    private final String target;

    /**
     * 构造函数
     *
     * @param entryId 条目标识符
     * @param target  目标存储
     */
    public EntryNotFoundException(String entryId, String target) {
        super(String.format("Memory entry not found: id=%s, target=%s", entryId, target));
        this.entryId = entryId;
        this.target = target;
    }

    /**
     * 构造函数（自定义消息）
     *
     * @param message 异常消息
     * @param entryId 条目标识符
     * @param target  目标存储
     */
    public EntryNotFoundException(String message, String entryId, String target) {
        super(message);
        this.entryId = entryId;
        this.target = target;
    }

    /**
     * 获取条目标识符
     *
     * @return 条目标识符
     */
    public String getEntryId() {
        return entryId;
    }

    /**
     * 获取目标存储
     *
     * @return 目标存储标识
     */
    public String getTarget() {
        return target;
    }
}
