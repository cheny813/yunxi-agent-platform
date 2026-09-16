package io.yunxi.platform.agent.capability;

import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.shared.config.AgentDefinition;

/**
 * Agent 能力装配器。
 *
 * <p>每个实现负责一类能力的装配决策（读配置、判启用条件、向 Builder 注册），
 * 由 {@code AgentConfigurer} 统一遍历调用。新增一类能力时只需新增一个实现类，
 * 无需修改装配主流程。</p>
 *
 * <p>实现类以 Spring 组件形式存在时会被自动收集；无状态的实现也可直接构造并传入。</p>
 *
 * @author yunxi-agent-platform
 */
public interface AgentCapability {

    /**
     * 能力名称，用于日志与问题定位。
     *
     * @return 能力名称
     */
    String name();

    /**
     * 装配顺序：数值小的先执行。
     *
     * <p>装配顺序会影响 Builder 上后注册者覆盖先注册者的场景，需要覆盖关系的实现应显式排序。</p>
     *
     * @return 顺序值
     */
    default int order() {
        return 100;
    }

    /**
     * 判断该能力对给定定义是否启用。
     *
     * <p>返回 false 时装配方法不会被调用。默认全部启用，由各实现按自身配置条件判定。</p>
     *
     * @param definition Agent 定义
     * @return 启用返回 true
     */
    default boolean supports(AgentDefinition definition) {
        return true;
    }

    /**
     * 向 Builder 注册该能力。
     *
     * @param builder    Agent Builder
     * @param definition Agent 定义
     */
    void configure(HarnessAgent.Builder builder, AgentDefinition definition);
}
