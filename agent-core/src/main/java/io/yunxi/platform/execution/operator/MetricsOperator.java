package io.yunxi.platform.execution.operator;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.EventOperator;
import reactor.core.publisher.Flux;

/**
 * 指标算子：统计事件流中的 token/耗时/阶段计数，输出结构化日志指标。
 *
 * <p>对 BLOCKING 与 STREAMING 一致作用；QUICK 模式也走本算子，
 * 保证两种模式都有可观测指标。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class MetricsOperator implements EventOperator {

    private static final Logger log = LoggerFactory.getLogger(MetricsOperator.class);

    @Override
    public int getOrder() {
        return 600;
    }

    @Override
    public Flux<AgentEvent> apply(Flux<AgentEvent> events, ExecutionContext ctx) {
        AtomicLong contentTokens = new AtomicLong(0);
        AtomicLong eventCount = new AtomicLong(0);
        long start = System.nanoTime();

        return events
                .doOnNext(e -> {
                    eventCount.incrementAndGet();
                    if (e.getType() == AgentEventType.AGENT_RESULT) {
                        contentTokens.incrementAndGet();
                        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
                        log.info("Metrics[{}]: agentName={}, userId={}, conversationId={}, events={}, elapsedMs={}",
                                ctx.getRequest().isQuickMode() ? "QUICK" : "STREAM",
                                ctx.getAgentName(), ctx.getUserId(), ctx.getConversationId(),
                                eventCount.get(), elapsedMs);
                    }
                })
                .doOnError(err -> log.warn("Metrics: event stream error, agentName={}, err={}",
                        ctx.getAgentName(), err.getMessage()))
                .doOnComplete(() -> {
                    if (contentTokens.get() == 0) {
                        // 仅当没有 AGENT_RESULT 时也记录一次结束指标
                        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
                        log.info("Metrics[{}]: agentName={}, userId={}, conversationId={}, events={}, elapsedMs={} (completed)",
                                ctx.getRequest().isQuickMode() ? "QUICK" : "STREAM",
                                ctx.getAgentName(), ctx.getUserId(), ctx.getConversationId(),
                                eventCount.get(), elapsedMs);
                    }
                });
    }
}
