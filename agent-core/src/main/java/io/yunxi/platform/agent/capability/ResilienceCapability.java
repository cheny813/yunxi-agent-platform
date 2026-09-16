package io.yunxi.platform.agent.capability;

import java.time.Duration;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;

/**
 * 推理健壮性能力：重试、降级模型、拒绝即停与模型调用超时。
 *
 * <p>全部读取全局配置，未配置的项保持框架默认。超时按毫秒配置，仅在为正数时生效。</p>
 *
 * @author yunxi-agent-platform
 */
public class ResilienceCapability implements AgentCapability {

    private final AgentscopeCoreProperties properties;

    public ResilienceCapability(AgentscopeCoreProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "resilience";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void configure(HarnessAgent.Builder builder, AgentDefinition definition) {
        AgentscopeCoreProperties.ResilienceProperties r = properties.getResilience();
        if (r.getMaxRetries() != null) {
            builder.maxRetries(r.getMaxRetries());
        }
        if (r.getFallbackModel() != null && !r.getFallbackModel().isBlank()) {
            builder.fallbackModel(r.getFallbackModel());
        }
        builder.stopOnReject(r.isStopOnReject());
        if (r.getTimeoutMs() != null && r.getTimeoutMs() > 0) {
            builder.modelExecutionConfig(ExecutionConfig.builder()
                    .timeout(Duration.ofMillis(r.getTimeoutMs()))
                    .build());
        }
    }
}
