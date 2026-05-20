package io.yunxi.platform.framework.mcp;

import java.util.Map;

/**
 * MCP 工具缓存 Key 生成器接口
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface ToolCacheKeyGenerator {

    /**
     * 生成缓存 key
     *
     * @param serverName  服务器名称
     * @param toolName    工具名称
     * @param arguments   工具参数
     * @return 缓存 key
     */
    String generate(String serverName, String toolName, Map<String, Object> arguments);
}