package io.yunxi.platform.agent.middleware;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

/**
 * 执行指标观测中间件（AgentScope-Java 原生扩展件，onAgent 钩子）。
 *
 * <p>统计单次调用的产出事件数与耗时，输出结构化日志：事件数与耗时按会话归属，
 * 便于按会话回溯执行开销。仅观测、不改写事件内容。</p>
 *
 * <p><b>Why 中间件而非引擎内联算子</b>：指标观测是典型的横切关切。原先挂在
 * {@code AgentExecutionEngine} 的流式通道上，导致阻塞通道没有指标、结构化输出通道
 * 要走另一条也带指标的路径（三处各写一份）。移到 {@code onAgent} 后，
 * 任何调用入口（阻塞 / 流式 / 结构化 / 子代理转发）自动全覆盖，且不需要引擎知道这件事。</p>
 *
 * @author yunxi-agent-platform
 */
public class AgentMetricsMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentMetricsMiddleware.class);

    @Override
    public int order() {
        // 高于 AgentPhaseMiddleware 的 100：指标要覆盖阶段归集自身的开销
        return 200;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent,
                                    RuntimeContext ctx,
                                    AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String agentName = agent == null ? "unknown" : agent.getName();
        String userId = ctx == null ? null : ctx.getUserId();
        String sessionId = ctx == null ? null : ctx.getSessionId();

        AtomicLong eventCount = new AtomicLong(0);
        AtomicLong resultCount = new AtomicLong(0);
        long start = System.nanoTime();

        return next.apply(input)
                .doOnNext(e -> {
                    eventCount.incrementAndGet();
                    if (e.getType() == AgentEventType.AGENT_RESULT) {
                        resultCount.incrementAndGet();
                        log.info("Metrics: agentName={}, userId={}, conversationId={}, events={}, elapsedMs={}",
                                agentName, userId, sessionId, eventCount.get(), elapsedMs(start));
                    }
                })
                .doOnError(err -> log.warn("Metrics: event stream error, agentName={}, err={}",
                        agentName, err.getMessage()))
                .doOnComplete(() -> {
                    if (resultCount.get() == 0) {
                        log.info("Metrics: agentName={}, userId={}, conversationId={}, events={}, elapsedMs={} (completed)",
                                agentName, userId, sessionId, eventCount.get(), elapsedMs(start));
                    }
                });
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
