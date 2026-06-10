package io.yunxi.platform.tracing;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.Tracer;
import io.agentscope.core.tracing.TracerRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.function.Supplier;

/**
 * OpenTelemetry 实现的 AgentScope Tracer
 * <p>通过实现 {@link Tracer} 接口，在 Agent/Model/Tool 三个层次创建 OpenTelemetry Span。</p>
 *
 * @see Tracer
 * @see TracerRegistry
 * @see ObservabilityAutoConfiguration
 */
public class OpenTelemetryTracer implements Tracer {

    private final io.opentelemetry.api.trace.Tracer otelTracer;

    public OpenTelemetryTracer(io.opentelemetry.api.trace.Tracer otelTracer) {
        this.otelTracer = otelTracer;
    }

    @Override
    public Mono<Msg> callAgent(AgentBase agent, List<Msg> msgs, Supplier<Mono<Msg>> supplier) {
        Span span = otelTracer.spanBuilder("agent.call")
                .setAttribute("agent.name", agent != null ? agent.getName() : "unknown")
                .setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return supplier.get()
                    .doOnSuccess(msg -> {
                        if (msg != null && msg.getTextContent() != null)
                            span.setAttribute("agent.response_length", msg.getTextContent().length());
                        span.end();
                    })
                    .doOnError(e -> { span.recordException(e); span.setStatus(StatusCode.ERROR); span.end(); });
        } catch (Exception e) { span.recordException(e); span.setStatus(StatusCode.ERROR); span.end(); throw e; }
    }

    @Override
    public Flux<ChatResponse> callModel(ChatModelBase model, List<Msg> msgs, List<ToolSchema> tools,
            GenerateOptions options, Supplier<Flux<ChatResponse>> supplier) {
        Span span = otelTracer.spanBuilder("llm.invoke")
                .setAttribute("llm.model", model != null ? model.getModelName() : "unknown")
                .setAttribute("llm.message_count", msgs != null ? msgs.size() : 0)
                .setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return supplier.get()
                    .doOnComplete(span::end)
                    .doOnError(e -> { span.recordException(e); span.setStatus(StatusCode.ERROR); span.end(); });
        } catch (Exception e) { span.recordException(e); span.setStatus(StatusCode.ERROR); span.end(); throw e; }
    }

    @Override
    public Mono<ToolResultBlock> callTool(Toolkit toolkit, ToolCallParam param, Supplier<Mono<ToolResultBlock>> supplier) {
        String toolName = param != null && param.getToolUseBlock() != null
                ? param.getToolUseBlock().getName() : "unknown";
        Span span = otelTracer.spanBuilder("tool.execute")
                .setAttribute("tool.name", toolName)
                .setAttribute("tool.toolkit", toolkit != null ? toolkit.getClass().getSimpleName() : "unknown")
                .setSpanKind(SpanKind.INTERNAL).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return supplier.get()
                    .doOnSuccess(result -> {
                        if (result != null && result.getOutput() != null)
                            span.setAttribute("tool.result_size", result.getOutput().size());
                        span.end();
                    })
                    .doOnError(e -> { span.recordException(e); span.setStatus(StatusCode.ERROR); span.end(); });
        } catch (Exception e) { span.recordException(e); span.setStatus(StatusCode.ERROR); span.end(); throw e; }
    }

    @Override
    public <TResp> TResp runWithContext(ContextView ctx, Supplier<TResp> supplier) {
        Context otelContext = ctx.getOrDefault(Context.class, Context.current());
        try (Scope ignored = otelContext.makeCurrent()) { return supplier.get(); }
    }
}
