package io.yunxi.platform.framework.observability;

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
 * OpenTelemetry 实现的 AgentScope Tracer。
 * <p>
 * 通过实现 {@link Tracer} 接口，在 Agent/Model/Tool 三个层次创建 OpenTelemetry Span。
 * 注册到 {@link TracerRegistry} 后，SDK 内部在 {@code AgentBase.call()}、模型推理、
 * 工具执行时自动触发，无需对业务代码做任何侵入式修改。
 * </p>
 *
 * <h3>Span 树结构</h3>
 *
 * <pre>
 * agent.call          (Agent 调用入口)
 *   ├── react.iteration 1   (由 ReActSpanHook 创建)
 *   │     ├── llm.invoke    (LLM 推理)
 *   │     └── tool.execute  (工具执行)
 *   ├── react.iteration 2
 *   │     ├── llm.invoke
 *   │     └── tool.execute
 *   └── ...
 * </pre>
 *
 * <h3>Span 属性</h3>
 * <table border="1">
 * <caption>各 Span 携带的属性</caption>
 * <tr>
 * <th>Span 名称</th>
 * <th>属性</th>
 * <th>说明</th>
 * </tr>
 * <tr>
 * <td>agent.call</td>
 * <td>agent.name, agent.response_length</td>
 * <td>Agent 名称和响应大小</td>
 * </tr>
 * <tr>
 * <td>llm.invoke</td>
 * <td>llm.model, llm.message_count</td>
 * <td>模型名称和输入消息数</td>
 * </tr>
 * <tr>
 * <td>tool.execute</td>
 * <td>tool.name, tool.result_size</td>
 * <td>工具名称和结果大小</td>
 * </tr>
 * <tr>
 * <td>react.iteration</td>
 * <td>react.iteration, react.stop_requested</td>
 * <td>迭代次数和停止标记</td>
 * </tr>
 * </table>
 *
 * <h3>使用方式</h3>
 *
 * <pre>{@code
 * // 一行代码注册，SDK 自动触发
 * TracerRegistry.register(new OpenTelemetryTracer(otelTracer));
 * }</pre>
 *
 * @see Tracer
 * @see TracerRegistry
 * @see ReActSpanHook
 * @see ObservabilityAutoConfiguration
 */
public class OpenTelemetryTracer implements Tracer {

    private final io.opentelemetry.api.trace.Tracer otelTracer;

    /**
     * 构造 OpenTelemetryTracer。
     *
     * @param otelTracer OpenTelemetry Tracer 实例，通过
     *                   {@code OpenTelemetry.getTracer()} 获取
     */
    public OpenTelemetryTracer(io.opentelemetry.api.trace.Tracer otelTracer) {
        this.otelTracer = otelTracer;
    }

    /**
     * 追踪 Agent 调用。
     * <p>
     * 创建 {@code agent.call} Span，记录 Agent 名称和响应文本长度。
     * SDK 在 {@code AgentBase.call()} 方法中自动调用此方法，
     * 因此 {@link io.yunxi.platform.framework.agent.AgentGatewayImpl} 无需做任何修改。
     * </p>
     *
     * @param agent    被调用的 Agent 实例（如 HarnessAgent 包装的 ReActAgent）
     * @param msgs     输入消息列表
     * @param supplier 实际 Agent 调用逻辑的包装器
     * @return Agent 返回消息
     */
    @Override
    public Mono<Msg> callAgent(AgentBase agent, List<Msg> msgs,
            Supplier<Mono<Msg>> supplier) {
        Span span = otelTracer.spanBuilder("agent.call")
                .setAttribute("agent.name", agent != null ? agent.getName() : "unknown")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return supplier.get()
                    .doOnSuccess(msg -> {
                        if (msg != null && msg.getTextContent() != null) {
                            span.setAttribute("agent.response_length",
                                    msg.getTextContent().length());
                        }
                        span.end();
                    })
                    .doOnError(e -> {
                        span.recordException(e);
                        span.setStatus(StatusCode.ERROR);
                        span.end();
                    });
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            span.end();
            throw e;
        }
    }

    /**
     * 追踪 LLM 模型调用。
     * <p>
     * 创建 {@code llm.invoke} Span，记录模型名称和输入消息数量。
     * ReActAgent 在每轮迭代的推理阶段自动调用此方法。
     * </p>
     *
     * @param model    被调用的模型实例（如 {@code DashScopeModelProvider}）
     * @param msgs     输入消息列表
     * @param tools    可用的工具 Schema 列表
     * @param options  生成选项（temperature、max_tokens 等）
     * @param supplier 实际模型调用逻辑的包装器
     * @return 模型响应流
     */
    @Override
    public Flux<ChatResponse> callModel(ChatModelBase model, List<Msg> msgs,
            List<ToolSchema> tools, GenerateOptions options,
            Supplier<Flux<ChatResponse>> supplier) {
        Span span = otelTracer.spanBuilder("llm.invoke")
                .setAttribute("llm.model", model != null ? model.getModelName() : "unknown")
                .setAttribute("llm.message_count",
                        msgs != null ? msgs.size() : 0)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return supplier.get()
                    .doOnComplete(() -> span.end())
                    .doOnError(e -> {
                        span.recordException(e);
                        span.setStatus(StatusCode.ERROR);
                        span.end();
                    });
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            span.end();
            throw e;
        }
    }

    /**
     * 追踪工具调用。
     * <p>
     * 创建 {@code tool.execute} Span，记录工具名称和执行结果大小。
     * ReActAgent 在每轮迭代的行动阶段自动调用此方法。
     * 工具名称通过 {@code param.getToolUseBlock().getName()} 获取。
     * </p>
     *
     * @param toolkit  工具包实例，包含一组已注册的工具
     * @param param    工具调用参数，包含工具名称和输入参数
     * @param supplier 实际工具执行逻辑的包装器
     * @return 工具执行结果
     */
    @Override
    public Mono<ToolResultBlock> callTool(Toolkit toolkit, ToolCallParam param,
            Supplier<Mono<ToolResultBlock>> supplier) {
        String toolName = param != null && param.getToolUseBlock() != null
                ? param.getToolUseBlock().getName()
                : "unknown";
        Span span = otelTracer.spanBuilder("tool.execute")
                .setAttribute("tool.name", toolName)
                .setAttribute("tool.toolkit",
                        toolkit != null ? toolkit.getClass().getSimpleName() : "unknown")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return supplier.get()
                    .doOnSuccess(result -> {
                        if (result != null && result.getOutput() != null) {
                            span.setAttribute("tool.result_size",
                                    result.getOutput().size());
                        }
                        span.end();
                    })
                    .doOnError(e -> {
                        span.recordException(e);
                        span.setStatus(StatusCode.ERROR);
                        span.end();
                    });
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            span.end();
            throw e;
        }
    }

    /**
     * 在 Reactor 上下文中传播 OpenTelemetry Context。
     * <p>
     * 由于 Reactive 编程模型可能跨线程切换，此方法确保 Span Context
     * 在 {@code doOnNext}、{@code flatMap} 等操作符间正确传递。
     * 如果没有找到 OTel Context，则使用 {@code Context.current()} 兜底。
     * </p>
     *
     * @param ctx      Reactor ContextView，可能包含 OpenTelemetry Context
     * @param supplier 实际执行逻辑
     * @param <TResp>  返回值类型
     * @return 执行结果
     */
    @Override
    public <TResp> TResp runWithContext(ContextView ctx, Supplier<TResp> supplier) {
        Context otelContext = ctx.getOrDefault(Context.class, Context.current());
        try (Scope ignored = otelContext.makeCurrent()) {
            return supplier.get();
        }
    }
}
