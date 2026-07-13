package io.yunxi.platform.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Micrometer 指标配置
 * <p>启用 @Timed 注解支持和 Prometheus 指标采集。</p>
 */
@Configuration
@EnableAspectJAutoProxy
public class MetricsConfig {

    /**
     * 注册 {@link TimedAspect} 以启用 @Timed 注解指标采集。
     *
     * @param registry 指标注册表
     * @return Timed 切面实例
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
