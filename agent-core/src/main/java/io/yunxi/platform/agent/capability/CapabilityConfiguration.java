package io.yunxi.platform.agent.capability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.yunxi.platform.agent.middleware.AgentMetricsMiddleware;
import io.yunxi.platform.agent.middleware.AgentPhaseMiddleware;
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
     * 可观测性能力：阶段归集与执行指标。
     *
     * <p>中间件持有跨调用状态（阶段轨迹缓存），故在此声明为单例 Bean，
     * 由 {@link ObservabilityCapability} 装配到每个 Agent 上。阶段轨迹的查询入口
     * 也经由该 Bean 暴露（见 {@code ObservabilityCapability#phaseMiddleware()}）。</p>
     *
     * <p>轨迹归集已下沉到统一执行引擎（按调用归集并落库，见 {@code AgentExecutionEngine}），
     * 此处不再装配归集中间件。属性补充中间件（{@link SpanAttributeMiddleware}）单独装配 ——
     * 它补的是「事件流里没有、只在中间件入参里」的信息（模型名与技能归属），
     * 与引擎侧归集配合才能产出完整节点负载。</p>
     */
    @Bean
    public ObservabilityCapability observabilityCapability() {
        return new ObservabilityCapability(
                new AgentPhaseMiddleware(),
                new AgentMetricsMiddleware(),
                new SpanAttributeMiddleware());
    }
}
