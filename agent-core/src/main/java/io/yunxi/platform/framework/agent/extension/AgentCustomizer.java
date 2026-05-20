package io.yunxi.platform.framework.agent.extension;

import io.agentscope.core.ReActAgent;
import io.yunxi.platform.shared.config.AgentDefinition;

/**
 * Agent 自定义装配器 — 扩展点（5% 复杂场景）
 * <p>
 * 当配置驱动无法满足需求时，实现此接口完全自定义 Agent 构建。
 * 例如：自定义 Agent 算法、特殊 Memory 策略、复杂的 ToolKit 初始化。
 * </p>
 *
 * <pre>
 * &#64;Component("myCustomizer")
 * public class MyAgentCustomizer implements AgentCustomizer {
 *     public ReActAgent customize(AgentDefinition definition, ReActAgent.Builder builder) {
 *         return builder
 *             .memory(new CustomMemoryStrategy())
 *             .maxIters(50)
 *             .build();
 *     }
 * }
 * </pre>
 *
 * @author yunxi-platform
 */
public interface AgentCustomizer {

    /**
     * 自定义 Agent 构建过程。
     *
     * @param definition Agent 配置定义
     * @param builder    Agent 构建器（已应用了配置驱动的基础参数）
     * @return 构建完成的 Agent 实例
     */
    ReActAgent customize(AgentDefinition definition, ReActAgent.Builder builder);
}