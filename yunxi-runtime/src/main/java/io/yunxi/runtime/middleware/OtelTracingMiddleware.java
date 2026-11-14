package io.yunxi.runtime.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.yunxi.observability.MetricRegistry;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 链路追踪中间件（order=90，最内层之一）。
 *
 * <p>职责：为 Agent 生命周期产出 OpenTelemetry span 并上报指标，闭环跨协议链路追踪缺失的内核缺口。
 * 三种 span：
 * <ul>
 *   <li>{@code invoke_agent <name>} —— 包裹整轮回复</li>
 *   <li>{@code chat <model>} —— 包裹每次模型调用</li>
 *   <li>{@code execute_tool} —— 包裹每次工具执行</li>
 * </ul>
 *
 * <p>未配置 OTel SDK 时（仅默认 no-op 提供者）每个钩子近乎零开销。指标经 {@link MetricRegistry} 统一汇出，
 * 生产装配可桥接到 Micrometer / Prometheus，避免在运行时内另起一份指标真相。
 */
public class OtelTracingMiddleware implements MiddlewareBase {

    private static final String INSTRUMENTATION_NAME = "io.yunxi.runtime";

    private final Tracer tracer;
    private final MetricRegistry metricRegistry;

    public OtelTracingMiddleware(MetricRegistry metricRegistry) {
        this.tracer = GlobalOpenTelemetry.get().getTracer(INSTRUMENTATION_NAME);
        this.metricRegistry = metricRegistry;
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        Span span = tracer.spanBuilder("invoke_agent " + agent.getName()).startSpan();
        span.setAttribute("agent.id", agent.getName());
        span.setAttribute("session.id", ctx == null ? "" : orEmpty(ctx.getSessionId()));
        metricRegistry.incrementCounter("agent.invocations");
        return next.apply(input).doFinally(signalType -> span.end());
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        Model model = input.model();
        Span span = tracer.spanBuilder("chat " + model.getModelName()).startSpan();
        span.setAttribute("model.id", model.getModelName());
        metricRegistry.incrementCounter("model.calls");
        return next.apply(input)
                .doOnError(e -> span.setStatus(StatusCode.ERROR, e.getMessage()))
                .doFinally(signalType -> span.end());
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        int toolCount = input.toolCalls() == null ? 0 : input.toolCalls().size();
        Span span = tracer.spanBuilder("execute_tool").startSpan();
        span.setAttribute("tool.count", (long) toolCount);
        metricRegistry.incrementCounter("tool.calls");
        return next.apply(input)
                .doOnError(e -> span.setStatus(StatusCode.ERROR, e.getMessage()))
                .doFinally(signalType -> span.end());
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
