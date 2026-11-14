package io.yunxi.app.mcp;

import io.yunxi.capability.McpServerSpec;
import java.util.List;
import java.util.Map;

/**
 * 应用平面。Nacos 目录条目 / REST 请求体的统一数据结构，字段对齐 MCP 官方 {@code server.json} 规范
 * （MCP 动态注册）。{@code status} 为运行时只读字段，不参与持久化。
 */
public class McpServerConfig {

    private String name;
    private String transport;
    private String url;
    private String command;
    private List<String> args;
    private Map<String, String> headers;
    private Map<String, String> env;
    private Integer timeout;
    private Boolean enabled = true;
    private String description;
    private String group;
    private String status;

    public McpServerSpec toSpec() {
        McpServerSpec.Transport t = "stdio".equalsIgnoreCase(transport)
                ? McpServerSpec.Transport.STDIO : McpServerSpec.Transport.SSE;
        if (t == McpServerSpec.Transport.STDIO) {
            return McpServerSpec.stdio(name, command, args != null ? args : List.of(), env);
        }
        return McpServerSpec.sse(name, url);
    }

    public static McpServerConfig fromSpec(McpServerSpec s) {
        McpServerConfig c = new McpServerConfig();
        c.setName(s.name());
        c.setTransport(s.transport() == McpServerSpec.Transport.STDIO ? "stdio" : "sse");
        c.setUrl(s.url());
        c.setCommand(s.command());
        c.setArgs(s.args());
        c.setEnv(s.env());
        c.setEnabled(s.enabled());
        return c;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
