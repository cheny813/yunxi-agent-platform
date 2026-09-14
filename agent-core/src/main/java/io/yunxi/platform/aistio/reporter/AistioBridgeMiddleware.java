package io.yunxi.platform.aistio.reporter;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import reactor.core.publisher.Flux;

/**
 * aistio 桥接中间件：在推理过程中把事件转发给 {@link AistioEventReporter}，供控制面实时观测。
 *
 * <p>该中间件<b>不自动装配进 Agent 链</b>（避免影响核心编排）；如需启用，在构建 Agent 时
 * 将其实例注册到 middleware 链即可。启用 gRPC 上报后，将
 * {@link LoggingAistioReporter} 替换为真正的 gRPC 实现即可完成实时上报。</p>
 */
@Component
public class AistioBridgeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AistioBridgeMiddleware.class);

    private final AistioEventReporter reporter;

    public AistioBridgeMiddleware(AistioEventReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        String sessionId = ctx.getSessionId();
        return next.apply(input)
                .doOnNext(event -> {
                    try {
                        reporter.emitEvent(sessionId, event.getClass().getSimpleName(), event);
                    } catch (Exception e) {
                        log.debug("aistio 事件上报失败（已忽略）: {}", e.getMessage());
                    }
                });
    }
}
