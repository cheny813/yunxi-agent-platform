package io.yunxi.capability;

import java.util.List;
import java.util.Map;

/**
 * 能力平面。单个 MCP 服务器的声明式描述。这是产品平面中 MCP 服务器唯一允许的形态；{@link McpDirectory} 是唯一通过
 * AgentScope-Java 的 {@code McpClientBuilder} 把定义变成存活的 {@code McpClientWrapper} 的组件。
 */
public record McpServerSpec(
        String name,
        Transport transport,
        String command,
        List<String> args,
        String url,
        Map<String, String> env,
        boolean enabled) {

    public enum Transport { STDIO, SSE }

    /** 便捷工厂：构建 STDIO 传输的服务器定义（默认启用）。 */
    public static McpServerSpec stdio(String name, String command, List<String> args, Map<String, String> env) {
        return new McpServerSpec(name, Transport.STDIO, command, args, null, env, true);
    }

    /** 便捷工厂：构建 SSE 传输的服务器定义（默认启用）。 */
    public static McpServerSpec sse(String name, String url) {
        return new McpServerSpec(name, Transport.SSE, null, List.of(), url, Map.of(), true);
    }
}
