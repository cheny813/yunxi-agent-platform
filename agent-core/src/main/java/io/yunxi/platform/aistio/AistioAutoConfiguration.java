package io.yunxi.platform.aistio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.yunxi.platform.aistio.interceptor.AistioAuthInterceptor;
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

    private final AistioAuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/agentscope/**")
                .excludePathPatterns("/agentscope/health");
    }
}
