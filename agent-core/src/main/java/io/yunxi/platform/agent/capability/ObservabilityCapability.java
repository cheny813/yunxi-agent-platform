package io.yunxi.platform.agent.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.trace.SpanAttributeMiddleware;

/**
 * 可观测性能力：属性补充。
 *
 * <p>把横切关切以 AgentScope-Java 原生 {@code MiddlewareBase} 形式装配到 Agent 上，
 * 使任何调用入口（阻塞 / 流式 / 结构化 / 子代理转发）自动获得覆盖。</p>
 *
 * <p>阶段归集（{@code PhaseMiddleware}）与执行指标（{@code MetricsMiddleware}）已随统一执行引擎
 * 迁出到 agent-execution 模块，经 {@code AgentMiddlewareContributor} SPI 贡献装配
 * （见 agent-core 的 AgentConfigurer），此处仅保留属性补充中间件。三者仍按各自 order() 排序生效。</p>
 *
 * @author yunxi-agent-platform
 */
public class ObservabilityCapability implements AgentCapability {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityCapability.class);

    private final SpanAttributeMiddleware spanAttributeMiddleware;

    public ObservabilityCapability(SpanAttributeMiddleware spanAttributeMiddleware) {
        this.spanAttributeMiddleware = spanAttributeMiddleware;
    }

    @Override
    public String name() {
        return "observability";
    }

    @Override
    public int order() {
        // 早于业务能力装配：观测件应贴近洋葱外层，排在业务中间件之外
        return 5;
    }

    @Override
    public boolean supports(AgentDefinition definition) {
        return true;
    }

    @Override
    public void configure(HarnessAgent.Builder builder, AgentDefinition definition) {
        builder.middleware(spanAttributeMiddleware);
        log.debug("Agent '{}' 已装配可观测性中间件（attributes）",
                definition == null ? "unknown" : definition.getName());
    }
}
