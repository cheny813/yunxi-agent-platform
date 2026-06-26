package io.yunxi.platform.tracing.middleware;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import reactor.core.publisher.Flux;

/**
 * ReAct 循环追踪 Middleware。
 *
 * <p>
 * 监听 V2.0 AgentEvent 事件流，创建三层 Span 结构：
 * agent.call → react.iteration → llm.invoke。
 * </p>
 *
 * <p>
 * 替代 V1.1 的 ReActSpanHook，使用 V2.0 MiddlewareBase + AgentEvent 机制。
 * 三层 Span 分别对应：Agent 整体调用、ReAct 单轮推理、LLM 模型调用，
 * 便于在 Jaeger/Zipkin 等链路追踪系统中定位性能瓶颈。
 * </p>
 */
public class ReActSpanMiddleware implements MiddlewareBase {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(ReActSpanMiddleware.class);

    /** Span 属性键：Agent 调用层 Span 的后缀标识 */
    private static final String KEY_CALL_SPAN = "_call";

    /** Span 属性键：ReAct 迭代层 Span 的前缀标识 */
    private static final String KEY_ITER_PREFIX = "_iter_";

    /** OpenTelemetry Tracer，用于创建和管理分布式链路追踪 Span */
    private final Tracer otelTracer;

    /**
     * 构造 ReAct 追踪 Middleware。
     *
     * @param otelTracer OpenTelemetry Tracer 实例，由 Tracing 模块提供
     */
    public ReActSpanMiddleware(Tracer otelTracer) {
        this.otelTracer = otelTracer;
    }

    /**
     * Agent 调用层拦截：创建最外层 agent.call Span。
     *
     * <p>
     * 在 Agent 整体调用开始时创建 Span，设置 agent.name 属性，
     * 将 Span 设为当前上下文，确保内层 Span 能自动建立父子关系。
     * 调用完成或异常时自动关闭 Span。
     * </p>
     *
     * @param agent 当前 Agent 实例
     * @param input Agent 输入
     * @param next  下一个 Middleware 的处理函数
     * @return AgentEvent 事件流
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        // 创建最外层 agent.call Span，标记为 SERVER 类型（表示被调用方）
        Span span = otelTracer.spanBuilder("agent.call")
                .setAttribute("agent.name", agent.getName())
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // 将当前 Span 设为上下文，使内层 onReasoning/onModelCall 自动关联为子 Span
            return next.apply(input)
                    .doOnComplete(span::end)
                    .doOnError(e -> {
                        // 记录异常信息并标记 Span 状态为 ERROR
                        span.recordException(e);
                        span.setStatus(StatusCode.ERROR);
                        span.end();
                    });
        }
    }

    /**
     * ReAct 推理层拦截：创建中间层 react.iteration Span。
     *
     * <p>
     * 每次 ReAct 循环迭代时触发，记录本轮推理的输入消息数量，
     * 便于分析 ReAct 循环的迭代次数和每轮输入规模。
     * </p>
     *
     * @param agent 当前 Agent 实例
     * @param input 推理输入，包含本轮消息列表
     * @param next  下一个 Middleware 的处理函数
     * @return AgentEvent 事件流
     */
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        // 创建中间层 react.iteration Span，记录本轮推理的输入消息数量
        Span span = otelTracer.spanBuilder("react.iteration")
                .setAttribute("react.input_count",
                        input.messages() != null ? input.messages().size() : 0)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return next.apply(input)
                    .doOnComplete(span::end)
                    .doOnError(e -> {
                        span.recordException(e);
                        span.setStatus(StatusCode.ERROR);
                        span.end();
                    });
        }
    }

    /**
     * 模型调用层拦截：创建最内层 llm.invoke Span。
     *
     * <p>
     * 每次调用 LLM 模型时触发，记录使用的模型名称，
     * 便于追踪具体的模型调用情况和延迟分布。
     * </p>
     *
     * @param agent 当前 Agent 实例
     * @param input 模型调用输入，包含模型配置信息
     * @param next  下一个 Middleware 的处理函数
     * @return AgentEvent 事件流
     */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        // 创建最内层 llm.invoke Span，记录调用的模型名称
        Span span = otelTracer.spanBuilder("llm.invoke")
                .setAttribute("model.name", "AgentScope V2.0")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return next.apply(input)
                    .doOnComplete(span::end)
                    .doOnError(e -> {
                        span.recordException(e);
                        span.setStatus(StatusCode.ERROR);
                        span.end();
                    });
        }
    }
}
