package io.yunxi.platform.framework.mcp;

import io.yunxi.platform.shared.util.mcp.McpDatabaseClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端配置
 *
 * <p>
 * 配置前缀：agentscope.mcp
 * MCP 服务器连接详情在 AgentscopeCoreProperties.mcpServers 中（前缀 agentscope.core.mcp-servers）
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "agentscope.mcp")
public class McpClientConfig {

    /**
     * 是否启用MCP功能
     */
    private boolean enabled = true;

    /**
     * 超时时间（毫秒）
     */
    private int timeout = 30000;

    /**
     * MCP数据库配置
     * 对应 agentscope.mcp.database 配置项
     */
    private DatabaseConfig database;

    /**
     * 创建 MCP 数据库客户端 Bean
     * 如果 database.enabled=true 且配置了 host 和 port
     */
    @Bean
    @ConditionalOnProperty(name = "agentscope.mcp.database.enabled", havingValue = "true")
    public McpDatabaseClient mcpDatabaseClient() {
        if (database == null || database.getHost() == null) {
            log.warn("MCP 数据库客户端未配置或配置不完整，跳过创建");
            return null;
        }
        log.info("创建 MCP 数据库客户端: {}:{}", database.getHost(), database.getPort());
        return new McpDatabaseClient(database.getHost(), database.getPort());
    }

    /**
     * MCP数据库配置
     */
    @Data
    public static class DatabaseConfig {
        /** 是否启用 */
        private boolean enabled = false;
        /** 数据库主机地址 */
        private String host;
        /** 数据库端口 */
        private int port = 40101;
    }
}
