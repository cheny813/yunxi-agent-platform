package io.yunxi.platform.framework.observability;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.HookEventType;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ReAct 循环追踪 Hook。
 * <p>
 * 监听 AgentScope SDK 自动发射的 Hook 事件，创建三层 Span 结构：
 * </p>
 * <ol>
 * <li><b>agent.call</b> — Agent 调用入口，由 PRE_CALL / POST_CALL 事件驱动</li>
 * <li><b>react.iteration</b> — 每轮推理迭代，由 PRE_REASONING / POST_REASONING
 * 事件驱动</li>
 * <li><b>llm.invoke / tool.execute</b> — 由 {@link OpenTelemetryTracer} 创建</li>
 * </ol>
 *
 * <p>
 * 与 {@link OpenTelemetryTracer} 的分工：
 * <ul>
 * <li>本 Hook 管理外层 Span（agent.call 和 react.iteration），通过 Hook 事件机制</li>
 * <li>{@link OpenTelemetryTracer} 管理内层 Span（llm.invoke 和 tool.execute），通过
 * Tracer 接口</li>
 * <li>AgentScope SDK 内置的 {@code AgentTraceHook(0)} 只做日志输出，与本 Hook 互补</li>
 * </ul>
 * </p>
 *
 * <h3>Span 树示例</h3>
 * 
 * <pre>
 * agent.call (agent.name="nutrition-assistant")
 *   ├── react.iteration (iteration=1)
 *   │     ├── llm.invoke (model=qwen-plus)
 *   │     └── tool.execute (tool=search_recipe)
 *   └── react.iteration (iteration=2)
 *         └── llm.invoke (model=qwen-plus)
 * </pre>
 *
 * <h3>并发安全</h3>
 * 使用 {@link ConcurrentHashMap} 以 Agent 实例标识为 key 存储活跃 Span。
 * 每次 PRE_CALL / PRE_REASONING 时调用 {@code makeCurrent()} 将新 Span 设为当前，
 * 确保其后的子 Span（由 {@link OpenTelemetryTracer} 创建）自动成为子节点。
 *
 * @see Hook
 * @see HookEventType
 * @see OpenTelemetryTracer
 * @see io.agentscope.harness.agent.hook.AgentTraceHook
 */
public class ReActSpanHook implements Hook {

    /** ConcurrentHashMap key 后缀：agent.call Span */
    private static final String KEY_CALL_SPAN = "_call";

    /** ConcurrentHashMap key 后缀：react.iteration Span */
    private static final String KEY_ITER_PREFIX = "_iter_";

    /** OpenTelemetry Tracer 实例 */
    private final io.opentelemetry.api.trace.Tracer otelTracer;

    /**
     * 活跃 Span 映射表。
     * key: agentKey + 后缀, value: OTel Span
     * 使用 ConcurrentHashMap 保证多个 Agent 并发执行时互不干扰。
     */
    private final ConcurrentHashMap<String, Span> spans = new ConcurrentHashMap<>();

    /**
     * 构造 ReAct 追踪 Hook。
     *
     * @param otelTracer OpenTelemetry Tracer 实例
     */
    public ReActSpanHook(io.opentelemetry.api.trace.Tracer otelTracer) {
        this.otelTracer = otelTracer;
    }

    /**
     * Hook 事件入口，根据事件类型分派到对应的处理方法。
     * <p>
     * 忽略的事件类型（PRE_ACTING / POST_ACTING / PRE_SUMMARY 等）由
     * {@link OpenTelemetryTracer} 通过 Tracer 接口处理。
     * </p>
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        switch (event.getType()) {
            case PRE_CALL -> handlePreCall((PreCallEvent) event);
            case POST_CALL -> handlePostCall((PostCallEvent) event);
            case PRE_REASONING -> handlePreReasoning((PreReasoningEvent) event);
            case POST_REASONING -> handlePostReasoning((PostReasoningEvent) event);
            case ERROR -> handleError((ErrorEvent) event);
        }
        return Mono.just(event);
    }

    /**
     * 根据 Agent 实例的内存地址生成唯一标识。
     * 用于 {@link #spans} 的 key，确保不同 Agent 实例的 Span 互相隔离。
     */
    private int agentKey(HookEvent event) {
        return event.getAgent() != null
                ? System.identityHashCode(event.getAgent())
                : 0;
    }

    // ========== agent.call Span ==========

    /**
     * 处理 PRE_CALL 事件：创建 {@code agent.call} Span。
     * <p>
     * {@code makeCurrent()} 将此 Span 设为当前线程的活跃 Span，
     * 后续由 {@link OpenTelemetryTracer} 创建的 llm.invoke / tool.execute
     * 将自动成为此 Span 的子节点。
     * </p>
     * <p>
     * 注意：AgentScope SDK 的 {@code AgentBase.call()} 会调用
     * {@code TracerRegistry.get().callAgent()}，但 {@code HarnessAgent}
     * 未实现此调用，因此通过 Hook 的 PRE_CALL 事件来创建 agent.call Span。
     * </p>
     */
    private void handlePreCall(PreCallEvent event) {
        Span span = otelTracer.spanBuilder("agent.call")
                .setAttribute("agent.name",
                        event.getAgent() != null ? event.getAgent().getName() : "unknown")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        span.makeCurrent();
        spans.put(agentKey(event) + KEY_CALL_SPAN, span);
    }

    /**
     * 处理 POST_CALL 事件：结束 {@code agent.call} Span。
     */
    private void handlePostCall(PostCallEvent event) {
        Span span = spans.remove(agentKey(event) + KEY_CALL_SPAN);
        if (span != null)
            span.end();
    }

    // ========== react.iteration Span ==========

    /**
     * 处理 PRE_REASONING 事件：创建 {@code react.iteration} Span。
     * <p>
     * 通过反射获取 Memory 的 {@code getCurrentStep()} 方法确定当前迭代次数。
     * {@code makeCurrent()} 将此 Span 设为当前活跃 Span，
     * 使本轮迭代中的 llm.invoke 自动成为其子节点。
     * </p>
     */
    private void handlePreReasoning(PreReasoningEvent event) {
        int iteration = 0;
        if (event.getMemory() != null) {
            try {
                var getStep = event.getMemory().getClass().getMethod("getCurrentStep");
                iteration = (int) getStep.invoke(event.getMemory());
            } catch (Exception ignored) {
                // Memory 的具体实现类可能不公开 getCurrentStep 方法
            }
        }

        Span span = otelTracer.spanBuilder("react.iteration")
                .setAttribute("react.iteration", iteration)
                .setAttribute("react.input_count",
                        event.getInputMessages() != null ? event.getInputMessages().size() : 0)
                .startSpan();
        span.makeCurrent();
        spans.put(agentKey(event) + KEY_ITER_PREFIX, span);
    }

    /**
     * 处理 POST_REASONING 事件：结束对应的 {@code react.iteration} Span。
     * 记录 Agent 是否请求停止迭代（{@code react.stop_requested}）。
     */
    private void handlePostReasoning(PostReasoningEvent event) {
        Span span = spans.remove(agentKey(event) + KEY_ITER_PREFIX);
        if (span == null)
            return;
        span.setAttribute("react.stop_requested", event.isStopRequested());
        span.end();
    }

    // ========== 错误处理 ==========

    /**
     * 处理 ERROR 事件：标记并结束当前活跃的 Span。
     * <p>
     * 优先结束 react.iteration Span；如果不存在则尝试结束 agent.call Span。
     * 记录异常信息和 ERROR 状态码。
     * </p>
     */
    private void handleError(ErrorEvent event) {
        Span span = spans.remove(agentKey(event) + KEY_ITER_PREFIX);
        if (span == null) {
            span = spans.remove(agentKey(event) + KEY_CALL_SPAN);
        }
        if (span == null)
            return;
        if (event.getError() != null)
            span.recordException(event.getError());
        span.setStatus(StatusCode.ERROR);
        span.end();
    }

    /**
     * Hook 优先级。
     * <p>
     * 30 意味着在 {@code GracefulShutdownHook} 之后、
     * {@code TextToolCallParserHook}(45) 之前执行。
     * SDK 内置的 {@code AgentTraceHook} 优先级为 0，先于本 Hook。
     * </p>
     */
    @Override
    public int priority() {
        return 30;
    }
}
