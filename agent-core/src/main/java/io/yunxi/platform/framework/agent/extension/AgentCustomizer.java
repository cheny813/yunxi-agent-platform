package io.yunxi.platform.framework.agent.extension;

import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.shared.config.AgentDefinition;

/**
 * Agent 自定义装配器 — 扩展点（5% 复杂场景）
 * <p>
 * 当配置驱动无法满足需求时，实现此接口对已构建完成的 HarnessAgent 进行后处理。
 * 例如：自定义 Hook 注入、特殊执行策略、后处理逻辑。
 * </p>
 *
 * <pre>
 * &#64;Component("myCustomizer")
 * public class MyAgentCustomizer implements AgentCustomizer {
 *     public Agent customize(AgentDefinition definition, Agent agent) {
 *         if (agent instanceof HarnessAgent) {
 *             // 对 HarnessAgent 进行后处理
 *         }
 *         return agent;
 *     }
 * }
 * </pre>
 *
 * @author yunxi-platform
 */
public interface AgentCustomizer {

    /**
     * 自定义 Agent 构建后处理。
     * <p>
     * 注意：此接口接收已完成构建的 Agent 实例（HarnessAgent 包装），
     * 而非构建器。如需修改构建参数，应调整 AgentDefinition YAML 配置。
     * </p>
     *
     * @param definition Agent 配置定义
     * @param agent      已完成构建的 Agent 实例
     * @return 处理后的 Agent 实例
     */
    Agent customize(AgentDefinition definition, Agent agent);
}
