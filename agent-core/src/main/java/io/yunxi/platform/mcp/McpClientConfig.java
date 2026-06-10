package io.yunxi.platform.mcp;

import io.yunxi.platform.shared.util.mcp.McpDatabaseClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端配置。
 *
 * <p>
 * 配置前缀：agentscope.mcp
 * MCP 服务器连接详情在 AgentscopeCoreProperties.mcpServers 中（前缀
 * agentscope.core.mcp-servers）。
 * </p>
 *
 * <p>
 * 配置项说明：
 * - enabled：是否启用 MCP 功能，默认 true
 * - timeout：MCP 调用超时时间，默认 30 秒
 * - database：MCP 数据库客户端配置（可选）
 * </p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "agentscope.mcp")
public class McpClientConfig {

    /** 是否启用 MCP 功能，默认 true */
    private boolean enabled = true;

    /** MCP 调用超时时间（毫秒），默认 30 秒 */
    private int timeout = 30000;

    /** MCP 数据库配置（可选，启用后可通过 MCP 协议查询数据库） */
    private DatabaseConfig database;

    /**
     * 创建 MCP 数据库客户端 Bean。
     *
     * <p>
     * 仅当 agentscope.mcp.database.enabled=true 时创建。
     * 用于通过 MCP 协议连接外部数据库服务。
     * </p>
     *
     * @return McpDatabaseClient 实例，配置不完整时返回 null
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
     * MCP 数据库配置。
     *
     * <p>
     * 用于配置 MCP 数据库客户端的连接信息，支持通过 MCP 协议
     * 对外部数据库执行 SQL 查询。
     * </p>
     */
    @Data
    public static class DatabaseConfig {
        /** 是否启用数据库 MCP 客户端，默认 false */
        private boolean enabled = false;

        /** 数据库服务器地址 */
        private String host;

        /** 数据库服务器端口，默认 40101 */
        private int port = 40101;
    }
}
