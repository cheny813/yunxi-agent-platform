package io.yunxi.platform.framework.tool;

/**
 * 工具执行结果
 *
 * @author yunxi-agent-platform
 */
public class ToolResult {

    /**
     * 执行是否成功
     */
    private final boolean success;

    /**
     * 执行结果数据
     */
    private final Object result;

    /**
     * 错误信息（如果执行失败）
     */
    private final String error;

    /**
     * 结果类型（用于标记不同类型的结果）
     */
    private String resultType;

    /**
     * 执行耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 私有构造方法
     *
     * @param success 是否成功
     * @param result  结果数据
     * @param error   错误信息
     */
    private ToolResult(boolean success, Object result, String error) {
        this.success = success;
        this.result = result;
        this.error = error;
    }

    /**
     * 创建成功结果
     *
     * @param result 结果数据
     * @return ToolResult
     */
    public static ToolResult success(Object result) {
        ToolResult toolResult = new ToolResult(true, result, null);
        toolResult.resultType = result != null ? result.getClass().getSimpleName() : "Void";
        return toolResult;
    }

    /**
     * 创建成功结果（带类型）
     *
     * @param result     结果数据
     * @param resultType 结果类型
     * @return ToolResult
     */
    public static ToolResult success(Object result, String resultType) {
        ToolResult toolResult = new ToolResult(true, result, null);
        toolResult.resultType = resultType;
        return toolResult;
    }

    /**
     * 创建失败结果
     *
     * @param error 错误信息
     * @return ToolResult
     */
    public static ToolResult error(String error) {
        return new ToolResult(false, null, error);
    }

    /**
     * 创建失败结果（带异常）
     *
     * @param exception 异常对象
     * @return ToolResult
     */
    public static ToolResult error(Exception exception) {
        return error(exception.getMessage());
    }

    /**
     * 是否执行成功
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取结果数据
     *
     * @return 结果数据
     */
    public Object getResult() {
        return result;
    }

    /**
     * 获取错误信息
     *
     * @return 错误信息
     */
    public String getError() {
        return error;
    }

    /**
     * 获取结果类型
     *
     * @return 结果类型
     */
    public String getResultType() {
        return resultType;
    }

    /**
     * 设置结果类型
     *
     * @param resultType 结果类型
     */
    public void setResultType(String resultType) {
        this.resultType = resultType;
    }

    /**
     * 获取执行耗时
     *
     * @return 执行耗时（毫秒）
     */
    public Long getDurationMs() {
        return durationMs;
    }

    /**
     * 设置执行耗时
     *
     * @param durationMs 执行耗时（毫秒）
     */
    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    /**
     * 获取字符串结果
     */
    public String getStringResult() {
        return result != null ? result.toString() : null;
    }

    /**
     * 获取指定类型的结果
     */
    @SuppressWarnings("unchecked")
    public <T> T getResult(Class<T> type) {
        if (result == null) {
            return null;
        }
        if (type.isInstance(result)) {
            return (T) result;
        }
        throw new ClassCastException("无法将结果 " + result + " 转换为类型 " + type.getName());
    }
}
