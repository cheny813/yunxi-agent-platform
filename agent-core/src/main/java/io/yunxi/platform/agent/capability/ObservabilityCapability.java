package io.yunxi.platform.agent.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.agent.middleware.AgentMetricsMiddleware;
import io.yunxi.platform.agent.middleware.AgentPhaseMiddleware;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.trace.SpanAttributeMiddleware;

/**
 * 可观测性能力：轨迹归集、阶段归集、属性补充与执行指标。
 *
 * <p>把横切关切以 AgentScope-Java 原生 {@code MiddlewareBase} 形式装配到 Agent 上，
 * 使任何调用入口（阻塞 / 流式 / 结构化 / 子代理转发）自动获得覆盖，
 * 不需要执行引擎逐个通道接线。</p>
 *
 * <p>轨迹归集已下沉到统一执行引擎（由引擎在内容流装配阶段按调用归集并落库，
 * 见 {@code AgentExecutionEngine}），此处不再持有归集中间件。其余三者按洋葱顺序由外向内：
 * {@link SpanAttributeMiddleware}（order 900）在模型调用与动作钩子上补齐
 * 「事件流里没有、只在中间件入参里」的信息；{@link AgentPhaseMiddleware}（order 100）
 * 推导阶段并注入状态标记；{@link AgentMetricsMiddleware}（order 200）统计全链开销。</p>
 *
 * <p>中间件共享单例是安全的：轨迹归集在每次调用内新建归集会话，
 * 属性按运行时上下文作用域存放，阶段轨迹缓存以会话为键。</p>
 *
 * @author yunxi-agent-platform
 */
public class ObservabilityCapability implements AgentCapability {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityCapability.class);

    /** 阶段归集器：既装配给 Agent，也供状态查询接口读取轨迹，故对外暴露单例 */
    private final AgentPhaseMiddleware phaseMiddleware;
    private final AgentMetricsMiddleware metricsMiddleware;
    private final SpanAttributeMiddleware spanAttributeMiddleware;

    public ObservabilityCapability(AgentPhaseMiddleware phaseMiddleware,
                                   AgentMetricsMiddleware metricsMiddleware,
                                   SpanAttributeMiddleware spanAttributeMiddleware) {
        this.phaseMiddleware = phaseMiddleware;
        this.metricsMiddleware = metricsMiddleware;
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
        // 装配顺序无关：中间件按 order() 排序，此处顺序仅为可读性（由外向内书写）
        builder.middleware(spanAttributeMiddleware);
        builder.middleware(metricsMiddleware);
        builder.middleware(phaseMiddleware);
        log.debug("Agent '{}' 已装配可观测性中间件（attributes + metrics + phase）",
                definition == null ? "unknown" : definition.getName());
    }

    /** 供状态查询接口读取阶段轨迹。 */
    public AgentPhaseMiddleware phaseMiddleware() {
        return phaseMiddleware;
    }
}
