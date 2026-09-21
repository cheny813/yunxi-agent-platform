package io.yunxi.platform.aistio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.yunxi.platform.aistio.interceptor.AistioAuthInterceptor;
import io.yunxi.platform.aistio.orchestration.OrchestrationCapability;
import io.yunxi.platform.aistio.orchestration.WebhookOrchestrationTrigger;
import lombok.RequiredArgsConstructor;

/**
 * aistio 集成自动配置。
 *
 * <p>仅当 {@code yunxi.aistio.enabled=true} 时生效，负责将 {@link AistioAuthInterceptor} 挂到
 * {@code /agentscope/**}（健康检查路径除外）。控制器、注册器、各服务均自带
 * {@code @ConditionalOnProperty}，与本配置共同保证「关闭时不装配任何契约组件」。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "yunxi.aistio", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AistioAutoConfiguration implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AistioAutoConfiguration.class);

    private final AistioAuthInterceptor authInterceptor;
    private final AistioIntegrationProperties properties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/agentscope/**")
                .excludePathPatterns("/agentscope/health");
    }

    /**
     * 编排触发侧（应用级单例，§3.3.1）。仅在显式开启编排触发时装配 —— 未配置 automation 绑定时
     * 装配出来只会是一个"处处报错"的 Bean，不如不装。
     *
     * <p>装配即做一次启动期无副作用探测（§3.3.4），结论写启动日志；探测失败只告警、不阻断启动
     * （aistio 控制面可能尚未就绪）。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "yunxi.aistio.trigger", name = "enabled", havingValue = "true")
    public WebhookOrchestrationTrigger orchestrationTrigger() {
        WebhookOrchestrationTrigger trigger = new WebhookOrchestrationTrigger(properties);
        OrchestrationCapability capability = trigger.capability();
        if (capability.available()) {
            log.info("aistio 编排触发已就绪: {}", capability.reason());
            capability.problems().forEach(p -> log.info("  - {}", p));
        } else {
            log.warn("aistio 编排触发不可用: {} | {}", capability.reason(), String.join(" | ", capability.problems()));
        }
        return trigger;
    }
}
