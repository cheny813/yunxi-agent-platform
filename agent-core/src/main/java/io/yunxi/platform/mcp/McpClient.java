package io.yunxi.platform.mcp;

import java.util.Map;

/**
 * MCP 客户端接口。
 *
 * <p>
 * 定义与 MCP 服务器通信的标准接口，包含工具调用和工具列表查询两个核心方法。
 * </p>
 *
 * <p>
 * 保留自 V2.0 升级：底层协议实现，不属于框架封装层。
 * V2.0 McpServerRegistrar + McpClientWrapper 处理框架级 MCP 注册，
 * 此接口处理 yunxi 特有的 MCP 调用协议。
 * </p>
 */
public interface McpClient {

    /**
     * 调用 MCP 工具。
     *
     * @param serverName MCP 服务器名称
     * @param toolName   工具名称
     * @param arguments  工具参数
     * @return 工具调用结果
     */
    Object callTool(String serverName, String toolName, Map<String, Object> arguments);

    /**
     * 获取指定 MCP 服务器的工具列表。
     *
     * @param serverName MCP 服务器名称
     * @return 工具列表（包含工具名称、描述、参数 Schema 等信息）
     */
    Map<String, Object> listTools(String serverName);
}
