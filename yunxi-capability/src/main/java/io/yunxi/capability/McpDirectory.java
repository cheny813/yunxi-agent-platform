package io.yunxi.capability;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.List;
import java.util.Optional;

/**
 * 能力平面。唯一的 MCP 注册路径。任何需要 MCP 工具的平面都必须经过此接口；没有平面可以直接调用
 * {@code McpClientBuilder}（MCP 连通性的唯一接入点）。
 */
public interface McpDirectory {

    /** 注册（或覆盖）一个 MCP 服务器定义。 */
    void register(McpServerSpec spec);

    /** 按名称移除一个 MCP 服务器定义。 */
    void unregister(String name);

    /** 按名称读取定义；不存在时返回空。 */
    Optional<McpServerSpec> get(String name);

    /** 返回当前全部 MCP 服务器定义。 */
    List<McpServerSpec> all();

    /** 为给定定义构建（并初始化）一个存活的客户端包装器。 */
    McpClientWrapper buildClient(McpServerSpec spec);

    /**
     * 探活：判定指定名称的 MCP 服务器当前是否可达（惰性建连 + 工具列举）。
     * 定义不存在时返回 false；不抛异常。供构建期注册失败的恢复探测使用。
     */
    boolean probe(String serverName);
}
