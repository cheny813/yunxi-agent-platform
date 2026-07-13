package io.yunxi.platform.agent;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.shared.config.AgentDefinition;

/**
 * Agent 自定义扩展 SPI。
 *
 * <p>
 * 允许 Agent 创建后、注册前对 Agent 实例进行二次定制。
 * 通过 AgentDefinition YAML 的 {@code extensions.agentCustomizer}
 * 指定实现类，AgentConfigurer 在构建 HarnessAgent 后调用。
 * </p>
 *
 * <p>
 * 统一为单一定制入口，简化扩展机制，便于在 Agent 构建完成后进行二次加工。
 * </p>
 *
 * @see io.yunxi.platform.agent.AgentConfigurer
 */
public interface AgentCustomizer {

    /**
     * 定制 Agent 实例。
     *
     * <p>
     * 在 HarnessAgent 构建完成后、注册到容器前调用。
     * 可替换 Agent 实例（例如用装饰器包装），
     * 也可直接修改 Agent 的属性（但通常建议通过 AgentDefinition YAML 配置）。
     * </p>
     *
     * @param definition Agent 定义配置，包含 YAML 中声明的所有 Agent 配置信息
     * @param agent      构建完成的 Agent 实例，可对其进行修改或替换
     * @return 定制后的 Agent 实例，可以是修改后的原实例，也可以是全新的包装实例
     */
    Agent customize(AgentDefinition definition, Agent agent);
}
