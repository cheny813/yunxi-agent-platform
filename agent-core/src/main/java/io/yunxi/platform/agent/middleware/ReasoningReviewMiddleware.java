package io.yunxi.platform.agent.middleware;

import io.agentscope.core.agent.Agent;
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
 * 推理审查 Middleware
 *
 * <p>
 * V2.0-RC1 适配：ReasoningInput.getToolCalls() 在当前版本中被移除。
 * 当前使用策略名称进行审查判断，完整工具调用检查等待 API 稳定后启用。
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
    public Flux<AgentEvent> onReasoning(Agent agent, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnComplete(() -> {
            // V2.0-RC1: ReasoningInput.getToolCalls() removed, strategy-based review only
            if (needsReview()) {
                log.warn("推理需要人工审查: {}", getReviewReason());
            }
        });
    }

    private boolean needsReview() {
        return switch (strategy) {
            case "all" -> true;
            case "on-dangerous-tool" -> false; // V2.0-RC1: tool inspection not available
            case "keyword-match" -> true;
            default -> false;
        };
    }

    private String getReviewReason() {
        return switch (strategy) {
            case "all" -> "每次推理均需人工审查";
            case "on-dangerous-tool" -> "LLM 决定调用危险工具需要审查 (V2.0: 检查受限)";
            case "keyword-match" -> "推理内容涉及敏感关键词需要审查";
            default -> "推理需要人工审查";
        };
    }
}
