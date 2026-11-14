package io.yunxi.app.mcp;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用平面。MCP 动态注册的 Nacos 协调底座配置。对应文档《06. 配置指南 · MCP 动态注册配置》。
 *
 * <p>启用后：每个 MCP 服务器条目以全量快照形式持久化到 Nacos {@code dataId}（默认 {@code yunxi.mcp-servers.json}），
 * 多实例共享同一权威目录；Nacos 不可达时自动退化为本地内存目录（{@code staticServers} 可选种子）。
 */
@ConfigurationProperties(prefix = "yunxi.mcp.nacos")
public class McpNacosProperties {

    /** 是否启用 Nacos 协调底座；关闭时仅使用本地内存目录。 */
    private boolean enabled = false;
    /** Nacos 服务地址（host:port）。 */
    private String serverAddr = "127.0.0.1:8848";
    /** Nacos 命名空间。 */
    private String namespace = "public";
    /** MCP 服务器条目所在的 Nacos 分组。 */
    private String group = "YUNXI_MCP_GROUP";
    /** 全量快照持久化的 Nacos dataId。 */
    private String dataId = "yunxi.mcp-servers.json";
    /** 协调服务名（用于多实例选举/协调）。 */
    private String coordinatorService = "yunxi-mcp-coordinator";
    /** 协调服务所在分组。 */
    private String coordinatorGroup = "YUNXI_MCP_GROUP";

    /** Nacos 未启用时的本地种子服务器列表，用于单实例/离线场景下的优雅降级。 */
    private List<McpServerConfig> staticServers;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public String getCoordinatorService() {
        return coordinatorService;
    }

    public void setCoordinatorService(String coordinatorService) {
        this.coordinatorService = coordinatorService;
    }

    public String getCoordinatorGroup() {
        return coordinatorGroup;
    }

    public void setCoordinatorGroup(String coordinatorGroup) {
        this.coordinatorGroup = coordinatorGroup;
    }

    public List<McpServerConfig> getStaticServers() {
        return staticServers;
    }

    public void setStaticServers(List<McpServerConfig> staticServers) {
        this.staticServers = staticServers;
    }
}
