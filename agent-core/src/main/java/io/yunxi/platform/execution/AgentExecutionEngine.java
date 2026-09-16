package io.yunxi.platform.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.spi.AgentEventAdapter;
import io.yunxi.platform.execution.spi.ExecutionStrategy;
import io.yunxi.platform.execution.strategy.StreamingStrategy;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.trace.ReasoningSpan;
import io.yunxi.platform.trace.SpanKind;
import io.yunxi.platform.trace.TraceCollectorMiddleware;
import io.yunxi.platform.trace.TraceComposer;
import io.yunxi.platform.trace.TraceStore;
import io.yunxi.platform.trace.projection.ProjectionContext;
import io.yunxi.platform.trace.projection.SseProjection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * Agent 统一执行引擎（门面）。
 *
 * <p>ChatAppService 4 个 public 入口 + 阻塞结构化输出的收口点，统一执行骨架：</p>
 * <ol>
 *   <li>构造 {@link ExecutionContext}（request + conversation 关联）；</li>
 *   <li>拦截器链 {@code preHandleAll}（AuthResolve → Memory → IntentPipeline →
 *       RagRetrieval → Audit），失败时收口为错误结果；</li>
 *   <li>策略选择（BlockingStrategy / StreamingStrategy / StructuredBlockingStrategy）
 *       并执行（首个 supports 的胜出）；</li>
 *   <li>流式通道：指标观测 → 阶段归集（{@link AgentPhaseTracker}）→ 协议适配器
 *       {@link AgentEventAdapter}（含 onStart/onThinking/onError 生命周期钩子与事件转换）
 *       → start/thinking/content 编排 + 双层超时，流终结（doFinally）触发
 *       {@code postHandleAll} 收尾（审计落库）；</li>
 *   <li>阻塞通道：Msg 直接返回；null 收口为错误结果；请求结束后同步触发
 *       {@code postHandleAll} 收尾（post 仅做无流式依赖的落库动作）。</li>
 * </ol>
 *
 * <p>引擎协议无关：不依赖任何具体协议构建器，start/thinking/error
 * 等协议生命周期消息全部由 {@link AgentEventAdapter} 产出；接入新协议（AG-UI/WS）
 * 只需提供新的适配器实现。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AgentExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionEngine.class);

    private final DefaultInterceptorChain interceptorChain;
    private final List<ExecutionStrategy> strategies;
    private final AgentEventAdapter eventAdapter;
    private final AgentscopeCoreProperties properties;
    private final TraceComposer composer;
    private final TraceStore traceStore;
    private final SseProjection sseProjection;

    public AgentExecutionEngine(DefaultInterceptorChain interceptorChain,
                                List<ExecutionStrategy> strategies,
                                AgentEventAdapter eventAdapter,
                                AgentscopeCoreProperties properties,
                                TraceComposer composer,
                                TraceStore traceStore,
                                SseProjection sseProjection) {
        this.interceptorChain = interceptorChain;
        this.strategies = strategies;
        this.eventAdapter = eventAdapter;
        this.properties = properties;
        this.composer = composer;
        this.traceStore = traceStore;
        this.sseProjection = sseProjection;
    }

    /**
     * 执行一次对话。
     *
     * @param request      归一化执行请求（已含 agentName/conversationId/userId/streaming）
     * @param conversation 会话实体（可为 null，非会话型入口）
     * @return 执行结果（阻塞 Msg / 流式 SSE 流 / 错误）
     */
    public ExecutionResult execute(ExecutionRequest request, ConversationEntity conversation) {
        ExecutionContext ctx = new ExecutionContext(
                request, request.getConversationId(), request.getUserId(), request.getAgentName());
        ctx.setConversation(conversation);

        // 1. 拦截器链
        try {
            interceptorChain.preHandleAll(ctx);
        } catch (Exception e) {
            log.error("执行拦截器失败: agentName={}, conversationId={}",
                    request.getAgentName(), request.getConversationId(), e);
            ctx.setExecutionError(formatAgentError(e));
            interceptorChain.postHandleAll(ctx);
            return ExecutionResult.error(formatAgentError(e), ctx);
        }

        if (ctx.getResolvedAgent() == null) {
            log.error("Agent 未解析: agentName={}", request.getAgentName());
            ctx.setExecutionError("Agent 未解析: " + request.getAgentName());
            interceptorChain.postHandleAll(ctx);
            return ExecutionResult.error("Agent 未解析: " + request.getAgentName(), ctx);
        }

        // 2. 策略选择与执行
        ExecutionStrategy strategy = selectStrategy(ctx);
        if (strategy == null) {
            log.error("无匹配执行策略: streaming={}, quickMode={}",
                    request.isStreaming(), request.isQuickMode());
            ctx.setExecutionError("无匹配执行策略");
            interceptorChain.postHandleAll(ctx);
            return ExecutionResult.error("无匹配执行策略", ctx);
        }

        try {
            if (request.isStreaming()) {
                long streamStartNanos = System.nanoTime();
                @SuppressWarnings("unchecked")
                Flux<AgentEvent> events = (Flux<AgentEvent>) strategy.execute(ctx);
                // 指标观测与阶段归集已下沉为 Agent 侧的原生中间件
                // （AgentMetricsMiddleware / AgentPhaseMiddleware，见 ObservabilityCapability），
                // 引擎在此只做协议编排，不再承担横切关切。
                Flux<String> stream = composeStream(events, ctx)
                        // 流式通道 postHandle 收尾：流终结（完成/错误/取消）时触发审计等无流式依赖的收尾
                        .doFinally(sig -> {
                            long ms = (System.nanoTime() - streamStartNanos) / 1_000_000;
                            log.info("[TRACE-ENGINE] 流终结 signal={} elapsedMs={} agent={} conv={}",
                                    sig, ms, request.getAgentName(), request.getConversationId());
                            if (sig == SignalType.CANCEL) {
                                ctx.setExecutionError("客户端取消");
                            } else if (sig == SignalType.ON_ERROR && ctx.getExecutionError() == null) {
                                // 整体超时等未被 composeStream 兜底的异常：补记失败结果供审计判定
                                ctx.setExecutionError("流式执行异常");
                            }
                            interceptorChain.postHandleAll(ctx);
                        });
                return ExecutionResult.streaming(stream, ctx);
            }
            Msg result = (Msg) strategy.execute(ctx);
            if (result == null) {
                log.error("Agent 响应为空: agentName={}", request.getAgentName());
                ctx.setExecutionError("Agent 响应为空");
                interceptorChain.postHandleAll(ctx);
                return ExecutionResult.error("Agent 响应为空", ctx);
            }
            // 阻塞通道同步收尾：请求已结束（Msg 聚合完成），postHandle 仅做无流式依赖的落库动作
            interceptorChain.postHandleAll(ctx);
            return ExecutionResult.blocking(result, ctx);
        } catch (Exception e) {
            log.error("Agent 执行失败: agentName={}, conversationId={}",
                    request.getAgentName(), request.getConversationId(), e);
            ctx.setExecutionError(formatAgentError(e));
            interceptorChain.postHandleAll(ctx);
            return ExecutionResult.error(formatAgentError(e), ctx);
        }
    }

    private ExecutionStrategy selectStrategy(ExecutionContext ctx) {
        for (ExecutionStrategy strategy : strategies) {
            if (strategy.supports(ctx)) {
                return strategy;
            }
        }
        return null;
    }

    /**
     * 流式事件编排：start → thinking → content，双层超时。
     *
 * <p>内容流超时（A2A/深度模式 3 倍），整体 concat 超时（chatTimeout + 30s）。
 * 协议消息（start/thinking/error）全部由 {@link AgentEventAdapter} 生命周期钩子产出，
 * 引擎不依赖任何具体协议。</p>
     */
    private Flux<String> composeStream(Flux<AgentEvent> events, ExecutionContext ctx) {
        ExecutionRequest req = ctx.getRequest();
        boolean useA2A = req.isUseA2A() || req.isDeepMode();
        int timeoutSeconds = useA2A
                ? properties.getChatTimeoutSeconds() * 3
                : properties.getChatTimeoutSeconds();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        if (useA2A) {
            log.info("深度/A2A 协作模式已启用，超时时间: {}s", timeoutSeconds);
        }

        // 开始事件（消息形态由适配器决定，如携带会话 ID）
        Flux<String> startFlux = toFlux(eventAdapter.onStart(ctx));

        // 思考事件（thinkingText 由 StreamingStrategy 写入 ctx；展示形态由适配器按模式自决）
        String thinkingText = (String) ctx.getAttribute(StreamingStrategy.ATTR_THINKING_TEXT);
        Flux<String> thinkingFlux = toFlux(eventAdapter.onThinking(thinkingText, ctx));

        // 一次调用 = 一棵轨迹树 = 一个归集会话。会话持有跨事件的归集状态（节点栈、配对键、
        // 未决计数），必须复用而非逐事件重建 —— 那正是 M1 修复过的缺陷（见 §G V10-21）。
        // 整条事件流先经归集器成为语义快照流，再由协议投影消费（M2 切换点：
        // 归集结果直接供投影消费），同时旁路落库。
        String traceId = resolveTraceId(ctx);
        TraceComposer.Session session = traceId == null ? null : composer.session(traceId);
        boolean[] orchestrationInjected = {false};
        RuntimeContext runtimeContext = (RuntimeContext) ctx.getAttribute("yunxi.runtimeContext");

        Flux<String> contentFlux = events
                .timeout(timeout)
                .concatMap(event -> projectEvent(event, ctx, session, traceId, runtimeContext,
                        orchestrationInjected))
                .onErrorResume(e -> {
                    // 推理异常被兜底为 error 消息：同时记录结果摘要，供 postHandle 审计判定失败
                    ctx.setExecutionError(formatAgentError(e));
                    log.error("Agent 推理异常: {}", e.getMessage(), e);
                    String error = eventAdapter.onError(formatAgentError(e), ctx);
                    return error != null ? Flux.just(error) : Flux.empty();
                });

        return Flux.concat(startFlux, thinkingFlux, contentFlux)
                .timeout(Duration.ofSeconds(properties.getChatTimeoutSeconds() + 30));
    }

    /** null 安全的消息列表转 Flux（适配器钩子未实现/mock 场景返回 null 时降级为空流） */
    private static Flux<String> toFlux(List<String> messages) {
        return (messages == null || messages.isEmpty()) ? Flux.empty() : Flux.fromIterable(messages);
    }

    /**
     * 逐事件投影：归集为语义快照 → 投影为协议消息 → 旁路落库。
     *
     * <p>事件流里覆盖的语义由投影承载（文本 / 思考 / 工具调用 / 工具结果）；事件流里<b>没有</b>
     * 或投影不覆盖的事件（回合开始结束、人机交互、阶段标记、模型调用、透明事件）仍交由
     * {@link AgentEventAdapter} 处理 —— 它们携带 usage 记录、会话持久化、结果去重、
     * 阶段状态与透传等投影不负责的协议副作用。</p>
     *
     * <p>归集是状态化的：调用方必须复用传入的 {@code session}，逐事件投喂。投影与落库都吞掉异常，
     * 保证观测失败不中断执行。</p>
     */
    private Flux<String> projectEvent(AgentEvent event, ExecutionContext ctx,
                                      TraceComposer.Session session, String traceId,
                                      RuntimeContext runtimeContext, boolean[] orchestrationInjected) {
        List<String> out = new ArrayList<>();
        if (session != null) {
            try {
                List<ReasoningSpan> spans = session.accept(event);
                if (event instanceof ToolCallStartEvent start
                        && "todo_write".equals(start.getToolCallName())) {
                    injectTodoSnapshot(session, runtimeContext, start);
                }
                ProjectionContext pctx = ProjectionContext.of(traceId,
                        ctx.getRequest().getUserId(), ctx.getConversationId(), Map.of());
                for (ReasoningSpan span : spans) {
                    out.addAll(sseProjection.convert(span, pctx));
                }
                for (ReasoningSpan span : spans) {
                    try {
                        traceStore.append(span);
                    } catch (Exception ex) {
                        log.warn("轨迹落库失败: traceId={}: {}", traceId, ex.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("轨迹归集失败: traceId={}: {}", traceId, e.getMessage());
            }
        }

        AgentEventType type = event.getType();
        boolean projected = type == AgentEventType.TEXT_BLOCK_DELTA
                || type == AgentEventType.THINKING_BLOCK_DELTA
                || type == AgentEventType.TEXT_BLOCK_START || type == AgentEventType.TEXT_BLOCK_END
                || type == AgentEventType.THINKING_BLOCK_START || type == AgentEventType.THINKING_BLOCK_END
                || type == AgentEventType.TOOL_CALL_START || type == AgentEventType.TOOL_CALL_END
                || type == AgentEventType.TOOL_RESULT_END;
        if (session == null || !projected) {
            out.addAll(eventAdapter.convert(event, ctx));
        }
        return Flux.fromIterable(out);
    }

    /** 旁路读取 AgentState 任务清单，注入为 PLAN 节点（工具参数不在事件流里） */
    private void injectTodoSnapshot(TraceComposer.Session session, RuntimeContext runtimeContext,
                                    ToolCallStartEvent start) {
        if (runtimeContext == null) {
            return;
        }
        try {
            AgentState state = RuntimeContext.resolveAgentState(runtimeContext, null);
            if (state == null || state.getTasksContext() == null) {
                return;
            }
            List<Map<String, Object>> tasks = state.getTasksContext().getTasks().stream()
                    .map(t -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", t.getId());
                        item.put("subject", t.getSubject());
                        item.put("state", t.getState() == null ? null : t.getState().name());
                        return item;
                    })
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tasks", tasks);
            payload.put("taskCount", tasks.size());
            payload.put("toolCallId", start.getToolCallId());
            for (ReasoningSpan span : session.inject(SpanKind.PLAN, payload, 0L)) {
                traceStore.append(span);
            }
        } catch (Exception e) {
            log.debug("任务清单归集失败: {}", e.getMessage());
        }
    }

    /** 轨迹标识：未显式指定时按 userId:sessionId 生成，与存储键一致 */
    private static String resolveTraceId(ExecutionContext ctx) {
        String sessionId = ctx.getConversationId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String userId = ctx.getRequest().getUserId();
        return (userId == null || userId.isBlank()) ? sessionId : userId + ":" + sessionId;
    }

    /**
     * 格式化 Agent 调用异常为用户友好消息。
     *
     * <p>公开静态方法：门面层（ChatAppService）的 catch 兜底同样需要该格式化逻辑。</p>
     */
    public static String formatAgentError(Throwable e) {
        if (e instanceof IllegalArgumentException) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("different type of Path")) {
                log.warn("Path 文件系统类型不匹配，这通常发生在从 JAR 加载 classpath 资源时。"
                        + "可尝试设置 agentscope.extensions.skills.enabled=false", e);
                return "服务内部错误，请联系管理员";
            }
        }
        String message = e.getMessage();
        return message != null ? message : "未知错误";
    }
}
