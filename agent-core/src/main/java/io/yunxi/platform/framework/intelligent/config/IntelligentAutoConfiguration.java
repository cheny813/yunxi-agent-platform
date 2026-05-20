package io.yunxi.platform.framework.intelligent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * 智能化服务自动配置
 * <p>
 * 重构后仅保留 IntelligentLlmService 及其依赖的配置。
 * 已删除的死代码子服务：strategy, reflection, background, prediction,
 * prompt, recommendation, fallback, evolution, monitor, event, memory,
 * controller
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(IntelligentProperties.class)
public class IntelligentAutoConfiguration {

    // IntelligentLlmService 使用 @Service 注解自注册，
    // 无需在此显式声明 Bean
}
