package io.yunxi.platform.config;

import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

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
    // MCP 工具现已由 AgentConfigurer.registerMcpServers() 用框架原生 McpClientBuilder 注册，
    // 此处不再维护“仅读配置”的死 Bean。AgentscopeCoreProperties.getMcpServers() 仍由 AgentConfigurer 消费。
}
