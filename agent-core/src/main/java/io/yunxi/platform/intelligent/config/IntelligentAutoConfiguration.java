package io.yunxi.platform.intelligent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * 智能化服务自动配置
 * <p>
 * 仅装配 IntelligentLlmService 及其依赖的配置。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(IntelligentProperties.class)
public class IntelligentAutoConfiguration {

    // IntelligentLlmService 使用 @Service 注解自动注册，
    // 无需在此显式声明 Bean
}
