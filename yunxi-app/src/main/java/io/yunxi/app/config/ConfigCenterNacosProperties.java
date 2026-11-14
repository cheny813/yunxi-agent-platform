package io.yunxi.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用平面。ConfigCenter 的 Nacos 协调底座配置（Agent 定义共享存储）。
 * 采用 Nacos 标准的 dataId / group 全量快照范式，但使用本类独立前缀
 * {@code yunxi.config.nacos}，避免与平台内其他 Nacos 用途的 dataId / group 冲突。
 */
@ConfigurationProperties(prefix = "yunxi.config.nacos")
public class ConfigCenterNacosProperties {

    /** 是否启用 Nacos 作为 Agent 定义共享存储；关闭时 ConfigCenter 退化为本地内存。 */
    private boolean enabled = false;
    /** Nacos 服务地址（host:port）。 */
    private String serverAddr = "127.0.0.1:8848";
    /** Nacos 命名空间。 */
    private String namespace = "public";
    /** Agent 定义全量快照所在的 Nacos 分组。 */
    private String group = "YUNXI_CONFIG_GROUP";
    /** Agent 定义全量快照持久化的 Nacos dataId。 */
    private String dataId = "yunxi.agent-definitions.json";
    /** 租户 → Agent 分配持久化的 Nacos dataId（与 Agent 定义快照隔离，保证分配单一真相源独立可演进）。 */
    private String allocationDataId = "yunxi.tenant-agent-allocation.json";

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

    public String getAllocationDataId() {
        return allocationDataId;
    }

    public void setAllocationDataId(String allocationDataId) {
        this.allocationDataId = allocationDataId;
    }
}
