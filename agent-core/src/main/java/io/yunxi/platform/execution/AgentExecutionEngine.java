package io.yunxi.platform.execution;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.spi.AgentEventAdapter;
import io.yunxi.platform.execution.spi.ExecutionStrategy;
import io.yunxi.platform.execution.strategy.StreamingStrategy;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.entity.ConversationEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * Agent 统一执行引擎（门面）。
 *
 * <p>ChatAppService 4 个 public 入口 + 阻塞结构化输出的收口点，统一执行骨架：</p>
 * <ol>
 *   <li>构造 {@link ExecutionContext}（request + conversation 关联）；</li>
 *   <li>拦截器链 {@code preHandleAll}（AuthResolve → Memory → IntentPipeline →
 *       RagRetrieval → PermissionContext → Audit），失败时收口为错误结果；</li>
 *   <li>策略选择（BlockingStrategy / StreamingStrategy / StructuredBlockingStrategy）
 *       并执行（首个 supports 的胜出）；</li>
 *   <li>流式通道：事件算子链（Metrics → PhaseTracker）→ 协议适配器
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
    private final EventOperatorChain operatorChain;
    private final AgentEventAdapter eventAdapter;
    private final AgentscopeCoreProperties properties;

    public AgentExecutionEngine(DefaultInterceptorChain interceptorChain,
                                List<ExecutionStrategy> strategies,
                                EventOperatorChain operatorChain,
                                AgentEventAdapter eventAdapter,
                                AgentscopeCoreProperties properties) {
        this.interceptorChain = interceptorChain;
        this.strategies = strategies;
        this.operatorChain = operatorChain;
        this.eventAdapter = eventAdapter;
        this.properties = properties;
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
                @SuppressWarnings("unchecked")
                Flux<AgentEvent> events = (Flux<AgentEvent>) strategy.execute(ctx);
                Flux<AgentEvent> operated = operatorChain.apply(events, ctx);
                Flux<String> stream = composeStream(operated, ctx)
                        // 流式通道 postHandle 收尾：流终结（完成/错误/取消）时触发审计等无流式依赖的收尾
                        .doFinally(sig -> {
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

        // 内容事件流：算子链结果 → 适配器转换 → 双层错误兜底
        // 结束信号由 PhaseTracker 注入的 DONE 阶段标记（agent_status "处理完成"）承担，
        // 无需单独追加 finishFlux。
        Flux<String> contentFlux = events
                .timeout(timeout)
                .flatMapSequential(event -> Flux.fromIterable(eventAdapter.convert(event, ctx)))
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
