package io.yunxi.platform.framework.tool;

/**
 * 工具接口
 * <p>
 * 定义 Agent 可调用外部工具的标准接口
 * </p>
 *
 * @author yunxi-agent-platform
 */
public interface Tool {

    /**
     * 获取工具名称
     *
     * @return 工具名称（全局唯一）
     */
    String getName();

    /**
     * 获取工具描述
     * <p>
     * 描述应该说明工具的功能和使用场景，Agent 会根据这个描述决定是否调用该工具
     * </p>
     *
     * @return 工具描述
     */
    String getDescription();

    /**
     * 获取工具参数 JSON Schema
     * <p>
     * 定义工具接受的参数格式，用于参数验证和 Agent 理解
     * </p>
     *
     * @return JSON Schema 字符串
     */
    String getParameterSchema();

    /**
     * 执行工具
     *
     * @param input 工具输入参数
     * @return 工具执行结果
     * @throws ToolExecutionException 如果执行失败
     */
    ToolResult execute(ToolInput input) throws ToolExecutionException;

    /**
     * 检查工具是否可用
     *
     * @return true 如果工具可以正常执行
     */
    default boolean isEnabled() {
        return true;
    }
}
