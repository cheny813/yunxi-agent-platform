package io.yunxi.platform.framework.mcp;

import java.util.Map;

/**
 * MCP 瀹㈡埛绔帴鍙?
 * <p>
 * 定义涓?MCP 服务鍣ㄩ€氫俊鐨勬爣鍑嗘帴鍙?
 * </p>
 *
 */
public interface McpClient {

    /**
     * 调用 MCP 宸ュ叿
     *
     * @param serverName 服务鍣ㄥ悕绉?
     * @param toolName   宸ュ叿名称
     * @param arguments  宸ュ叿参数
     * @return 宸ュ叿调用结果
     */
    Object callTool(String serverName, String toolName, Map<String, Object> arguments);

    /**
     * 获取宸ュ叿列表
     *
     * @param serverName 服务鍣ㄥ悕绉?
     * @return 宸ュ叿列表
     */
    Map<String, Object> listTools(String serverName);
}