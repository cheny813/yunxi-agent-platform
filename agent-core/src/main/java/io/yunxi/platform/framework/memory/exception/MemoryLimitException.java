package io.yunxi.platform.framework.memory.exception;

/**
 * 记忆容量超限异常
 * <p>
 * 当尝试添加的记忆条目超过字符限制时抛出
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class MemoryLimitException extends RuntimeException {

    /**
     * 当前使用量
     */
    private final int currentUsage;

    /**
     * 限制值
     */
    private final int limit;

    /**
     * 目标存储（memory 或 user）
     */
    private final String target;

    /**
     * 构造函数
     *
     * @param currentUsage 当前使用量
     * @param limit        限制值
     * @param target       目标存储
     */
    public MemoryLimitException(int currentUsage, int limit, String target) {
        super(String.format("Memory limit exceeded: current usage %d chars, limit %d chars in %s",
                currentUsage, limit, target));
        this.currentUsage = currentUsage;
        this.limit = limit;
        this.target = target;
    }

    /**
     * 构造函数（自定义消息）
     *
     * @param message      异常消息
     * @param currentUsage 当前使用量
     * @param limit        限制值
     * @param target       目标存储
     */
    public MemoryLimitException(String message, int currentUsage, int limit, String target) {
        super(message);
        this.currentUsage = currentUsage;
        this.limit = limit;
        this.target = target;
    }

    /**
     * 获取当前使用量
     *
     * @return 当前使用量
     */
    public int getCurrentUsage() {
        return currentUsage;
    }

    /**
     * 获取限制值
     *
     * @return 限制值
     */
    public int getLimit() {
        return limit;
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
