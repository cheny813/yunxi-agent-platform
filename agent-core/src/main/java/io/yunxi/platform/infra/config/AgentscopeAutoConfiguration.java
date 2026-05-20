package io.yunxi.platform.infra.config;

import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * AgentScope 自动配置类
 *
 * <p>
 * 提供 AgentScope 框架的自动配置和 MCP 服务器配置。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agentscope.extensions", name = "autoConfigEnabled", havingValue = "true")
public class AgentscopeAutoConfiguration {

    /** 扩展功能配置属性 */
    private final AgentscopeExtensionProperties extensionConfig;
    /** AgentScope 基础配置属性 */
    private final AgentscopeCoreProperties properties;

    // ==================== MCP 服务器配置 ====================

    /**
     * 动态创建 MCP 服务器配置 Bean
     */
    @Bean
    public Map<String, Object> mcpServerBeans() {
        Map<String, Object> beans = new HashMap<>();

        log.info("开始加载 MCP 服务器配置...");

        Map<String, AgentscopeCoreProperties.McpServerConfig> mcpServers = properties.getMcpServers();
        log.info("properties.getMcpServers() = {}", mcpServers);

        if (mcpServers == null || mcpServers.isEmpty()) {
            log.warn("MCP 服务器配置为空，请检查 application-agentscope-mcp.yml 是否正确加载");
            return beans;
        }

        mcpServers.forEach((name, mcpConfig) -> {
            log.info("处理 MCP 服务器: {}, enabled: {}", name, mcpConfig.isEnabled());

            if (!mcpConfig.isEnabled()) {
                return;
            }

            try {
                Map<String, Object> mcpInfo = new HashMap<>();
                mcpInfo.put("name", name);
                mcpInfo.put("type", mcpConfig.getType());
                mcpInfo.put("command", mcpConfig.getCommand());
                mcpInfo.put("args", mcpConfig.getArgs());
                mcpInfo.put("url", mcpConfig.getUrl());
                mcpInfo.put("timeout", mcpConfig.getTimeout());
                mcpInfo.put("headers", mcpConfig.getHeaders());
                mcpInfo.put("env", mcpConfig.getEnv());

                beans.put(name, mcpInfo);
                log.info("MCP 服务器配置加载成功: {} ({}) - {}", name, mcpConfig.getType(), mcpConfig.getUrl());

            } catch (Exception e) {
                log.error("加载 MCP 服务器配置失败: {}", name, e);
            }
        });

        log.info("MCP 服务器配置加载完成，共 {} 个", beans.size());
        return beans;
    }
}
