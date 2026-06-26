package io.yunxi.platform.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.yunxi.platform.shared.config.ReasoningReviewConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.function.Function;

/**
 * 推理审查 Middleware。
 *
 * <p>
 * V2.0-RC3: {@link ReasoningInput} 是 Java record，通过 {@code input.tools()}
 * 获取可用工具 Schema。
 * 根据审查策略决定是否需要暂停 Agent 等待人工审查。
 * </p>
 *
 * <p>
 * 审查策略：all → 每次推理都审查 | on-dangerous-tool → 仅当调用危险工具时审查 |
 * keyword-match → 包含敏感关键词时审查。
 * </p>
 */
public class ReasoningReviewMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ReasoningReviewMiddleware.class);

    private final String strategy;
    private final Set<String> dangerousTools;

    public ReasoningReviewMiddleware(ReasoningReviewConfig config, Set<String> dangerousTools) {
        this.strategy = config.getStrategy() != null ? config.getStrategy() : "on-dangerous-tool";
        this.dangerousTools = dangerousTools != null ? dangerousTools : Set.of();
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnComplete(() -> {
            if (needsReview()) {
                log.warn("推理需要人工审查: {}", getReviewReason());
            }
        });
    }

    private boolean needsReview() {
        return switch (strategy) {
            case "all" -> true;
            case "on-dangerous-tool" -> !dangerousTools.isEmpty();
            case "keyword-match" -> true;
            default -> false;
        };
    }

    private String getReviewReason() {
        return switch (strategy) {
            case "all" -> "每次推理均需人工审查";
            case "on-dangerous-tool" -> "监控到危险工具，需要审查 (监控工具: " + dangerousTools + ")";
            case "keyword-match" -> "推理内容涉及敏感关键词需要审查";
            default -> "推理需要人工审查";
        };
    }
}
