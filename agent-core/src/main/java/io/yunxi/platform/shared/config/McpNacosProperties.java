package io.yunxi.platform.shared.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP 动态注册协调底座（Nacos 3.2.4）配置。
 * <p>绑定前缀 yunxi.mcp.nacos，单机/集群切换仅改 server-addr / namespace 即可。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "yunxi.mcp.nacos")
public class McpNacosProperties {

    /** 是否启用 Nacos 协调底座（关闭则退化为纯本地静态配置） */
    private boolean enabled = true;

    /** Nacos 服务器地址，单机 127.0.0.1:8848，集群逗号分隔 */
    private String serverAddr = "127.0.0.1:8848";

    /** 命名空间 ID（默认空串 = public） */
    private String namespace = "";

    /** 鉴权用户名（NACOS_AUTH_ENABLE=false 时留空） */
    private String username = "";

    /** 鉴权密码 */
    private String password = "";

    /** 目录配置 dataId（存储 mcp-servers 目录 JSON；Nacos 3.x dataId 不允许 '/'，用 '.' 作分隔符） */
    private String dataId = "yunxi.mcp-servers.json";

    /** 目录配置 group */
    private String group = "YUNXI_MCP_GROUP";

    /** 协调实例服务名（Naming 广播，用于跨 JVM 感知） */
    private String serviceName = "yunxi-mcp-coordinator";

    /**
     * 每个 MCP 服务器的 Naming 服务名前缀（serviceName = mcp-server-<name>，供服务发现与健康检查）。
     * 平台协调实例用 {@link #serviceName}（yunxi-mcp-coordinator），与此前缀无冲突。
     */
    private String mcpServerServicePrefix = "mcp-server-";

    /** AI Registry 配置 group（Skill/Agent/Prompt/AgentSpec 统一治理空间）。 */
    private String aiRegistryGroup = "YUNXI_AI_REGISTRY_GROUP";

    /** AI Registry dataId 前缀（dataId = prefix.<type>.<name>；Nacos 3.x 不允许 '/'，用 '.' 作分隔符）。 */
    private String aiRegistryDataIdPrefix = "yunxi.ai";
}
