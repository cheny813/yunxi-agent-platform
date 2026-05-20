package io.yunxi.platform.gateway;

import io.yunxi.platform.gateway.core.GatewayDispatcher;
import io.yunxi.platform.gateway.core.GatewaySessionManager;
import io.yunxi.platform.gateway.core.CoreAgentClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 网关自动配置
 *
 * <p>当 gateway.enabled=true（默认）时自动装配网关组件</p>
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(GatewayProperties.class)
@ComponentScan(basePackages = "io.yunxi.platform.gateway")
@ConditionalOnProperty(prefix = "gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GatewayAutoConfiguration {

    @Bean
    public WebClient gatewayWebClient(GatewayProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.getCoreUrl())
                .build();
    }

    @Bean
    public CoreAgentClient coreAgentClient(WebClient gatewayWebClient, GatewayProperties properties) {
        log.info("[Gateway] 初始化 CoreAgentClient, coreUrl={}", properties.getCoreUrl());
        return new CoreAgentClient(gatewayWebClient, properties);
    }

    @Bean
    public GatewaySessionManager gatewaySessionManager(GatewayProperties properties) {
        log.info("[Gateway] 初始化 SessionManager, storeType={}", properties.getSession().getStoreType());
        return new GatewaySessionManager(properties);
    }

    @Bean
    public GatewayDispatcher gatewayDispatcher(CoreAgentClient coreAgentClient,
                                                GatewaySessionManager sessionManager,
                                                GatewayProperties properties) {
        log.info("[Gateway] 初始化 Dispatcher, defaultAgent={}", properties.getDefaultAgent());
        return new GatewayDispatcher(coreAgentClient, sessionManager, properties);
    }
}
