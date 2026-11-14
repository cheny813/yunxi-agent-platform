package io.yunxi.runtime.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.observability.AuditEvent;
import io.yunxi.observability.AuditSink;
import io.yunxi.observability.MetricRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 人机协同（HITL）中间件（order=95）。
 *
 * <p>职责：在工具执行阶段观测并配对「人工确认」请求与结果，闭环 HITL 缺口（内核只产出事件、不消费）。
 * AgentScope-Java 在工具需要外部执行时发射 {@link RequireExternalExecutionEvent}（含 replyId）并挂起，
 * 待调用方把结果（带同一 replyId）回填后，框架发射 {@link ExternalExecutionResultEvent} 续跑。
 * 本中间件不向事件流注入结果（那由调用方经入口网关完成），只做：replyId 配对、审计落盘、待处理登记表。
 *
 * <p>{@link #pendingRequests()} 暴露当前挂起的外部执行请求，供入口网关在本轮之外的异步通道回填结果。
 */
public class HitlMiddleware implements MiddlewareBase {

    private final AuditSink auditSink;
    private final MetricRegistry metricRegistry;
    private final ConcurrentHashMap<String, List<ToolUseBlock>> pending = new ConcurrentHashMap<>();

    public HitlMiddleware(AuditSink auditSink, MetricRegistry metricRegistry) {
        this.auditSink = auditSink;
        this.metricRegistry = metricRegistry;
    }

    @Override
    public int order() {
        return 95;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnNext(event -> observe(event, agent, ctx));
    }

    private void observe(AgentEvent event, Agent agent, RuntimeContext ctx) {
        if (event instanceof RequireExternalExecutionEvent require) {
            pending.put(require.getReplyId(), require.getToolCalls());
            auditSink.emit(new AuditEvent(
                    "hitl.require",
                    ctx == null ? "" : orEmpty(ctx.getUserId()),
                    Instant.now(),
                    Map.of(
                            "agentId", agent.getName(),
                            "replyId", require.getReplyId(),
                            "toolCount", String.valueOf(require.getToolCalls().size()))));
        } else if (event instanceof ExternalExecutionResultEvent result) {
            pending.remove(result.getReplyId());
            metricRegistry.incrementCounter("hitl.resolved");
            auditSink.emit(new AuditEvent(
                    "hitl.result",
                    ctx == null ? "" : orEmpty(ctx.getUserId()),
                    Instant.now(),
                    Map.of(
                            "agentId", agent.getName(),
                            "replyId", result.getReplyId(),
                            "resultCount", String.valueOf(result.getToolResults().size()))));
        }
    }

    /**
     * 当前挂起、等待人工回填结果的外部执行请求。入口网关据此经本机异步通道回填工具结果。
     */
    public Map<String, List<ToolUseBlock>> pendingRequests() {
        return Map.copyOf(pending);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
