package io.yunxi.platform.ingress;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 统一接入网关自动配置。
 *
 * <p>本模块的全部 {@code @RestController} / {@code @Component} 已处于 boot 主类的组件扫描根
 * {@code io.yunxi.platform} 之下，由 {@code AgentPlatformApplication} 自动发现，无需额外
 * {@code @ComponentScan}。此处仅启用 {@link GatewayProperties} 绑定。</p>
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayAutoConfiguration {
}
