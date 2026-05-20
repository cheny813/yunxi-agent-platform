package io.yunxi.platform.framework.tool;

/**
 * 工具执行异常
 *
 * @author yunxi-agent-platform
 */
public class ToolExecutionException extends Exception {

    /** 工具名称 */
    private final String toolName;

    /**
     * 构造工具执行异常
     *
     * @param toolName 工具名称
     * @param message  错误消息
     */
    public ToolExecutionException(String toolName, String message) {
        super(message);
        this.toolName = toolName;
    }

    /**
     * 构造工具执行异常（带原因）
     *
     * @param toolName 工具名称
     * @param message  错误消息
     * @param cause    原始异常
     */
    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super(message, cause);
        this.toolName = toolName;
    }

    /**
     * 构造工具执行异常（仅带原因）
     *
     * @param toolName 工具名称
     * @param cause    原始异常
     */
    public ToolExecutionException(String toolName, Throwable cause) {
        super(cause);
        this.toolName = toolName;
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称
     */
    public String getToolName() {
        return toolName;
    }

    @Override
    public String getMessage() {
        return String.format("工具 [%s] 执行失败: %s", toolName, super.getMessage());
    }
}
