package io.yunxi.platform.agent.capability;

import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.shared.config.AgentDefinition;

/**
 * 运行时迭代与元工具能力。
 *
 * <p>按 Agent 定义中的运行时配置装配最大迭代次数；启用元工具开关时一并注册。
 * 定义中未给出运行时配置时保持框架默认，不做任何装配。</p>
 *
 * @author yunxi-agent-platform
 */
public class RuntimeCapability implements AgentCapability {

    @Override
    public String name() {
        return "runtime";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(AgentDefinition definition) {
        return definition != null && definition.getRuntime() != null;
    }

    @Override
    public void configure(HarnessAgent.Builder builder, AgentDefinition definition) {
        builder.maxIters(definition.getRuntime().getMaxIterations());
        if (definition.getRuntime().isEnableMetaTool()) {
            builder.enableMetaTool(true);
        }
    }
}
