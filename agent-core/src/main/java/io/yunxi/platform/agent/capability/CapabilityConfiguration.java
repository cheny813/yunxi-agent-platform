package io.yunxi.platform.agent.capability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.trace.SpanAttributeMiddleware;

/**
 * 内置能力的装配声明。
 *
 * <p>各能力依据全局配置判定自身启用条件，因此在本处统一注册为 Bean，由
 * {@link AgentCapabilityRegistry} 收集。新增能力时在此登记一行即可。</p>
 *
 * @author yunxi-agent-platform
 */
@Configuration
public class CapabilityConfiguration {

    @Bean
    public AgentCapability runtimeCapability() {
        return new RuntimeCapability();
    }

    @Bean
    public AgentCapability resilienceCapability(AgentscopeCoreProperties properties) {
        return new ResilienceCapability(properties);
    }

    @Bean
    public AgentCapability taskListCapability(AgentscopeCoreProperties properties) {
        return new TaskListCapability(properties);
    }

    @Bean
    public AgentCapability planModeCapability(AgentscopeCoreProperties properties) {
        return new PlanModeCapability(properties);
    }

    /**
     * 可观测性能力：属性补充。
     *
     * <p>阶段归集（{@code PhaseMiddleware}）与执行指标（{@code MetricsMiddleware}）已随统一执行引擎
     * 迁出到 agent-execution 模块，经 {@code AgentMiddlewareContributor} SPI 贡献装配
     * （见 agent-core 的 AgentConfigurer），此处仅注册属性补充单例。</p>
     */
    @Bean
    public ObservabilityCapability observabilityCapability() {
        return new ObservabilityCapability(new SpanAttributeMiddleware());
    }
}
