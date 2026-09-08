package io.yunxi.platform.agent.mcp;

import lombok.Data;

/**
 * MCP 服务器目录条目（GET /api/mcp/servers 接口返回结构，字段对齐 MCP 官方 server.json 规范）。
 * <p>对齐官方 MCP Registry server.json 规范，含 name/transport/url/status 等字段，
 * 便于生态互认。Nacos Naming 实例的 metadata 即按此 schema 填充。</p>
 */
@Data
public class McpServerEntry {

    /** 服务器名称（即工具组名） */
    private String name;

    /** server.json: transport（对应本平台 McpServerConfig.type：sse / stdio / streamable-http） */
    private String transport;

    /** sse/streamable-http 模式地址 */
    private String url;

    /** stdio 模式启动命令 */
    private String command;

    /** stdio 模式参数（空格分隔） */
    private String args;

    /** 自定义请求头（JSON） */
    private String headers;

    /** 环境变量（JSON） */
    private String env;

    /** 连接超时（毫秒） */
    private String timeout;

    /** 工具组名（默认与 name 相同） */
    private String group;

    /** 是否注入 Toolkit */
    private boolean enabled;

    /** 健康状态：UP / DOWN（来自 Naming 实例健康探测）/ local（Nacos 未启用降级） */
    private String status;

    /** 版本（预留，当前未知） */
    private String version;
}
