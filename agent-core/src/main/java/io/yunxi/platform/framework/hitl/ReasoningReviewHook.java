package io.yunxi.platform.framework.hitl;

import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.yunxi.platform.shared.config.ReasoningReviewConfig;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 推理审查 Hook
 *
 * <p>根据配置的审查策略对 LLM 推理结果进行审查。支持三种策略：
 * <ul>
 *   <li>all — 每次推理都需要审查</li>
 *   <li>on-dangerous-tool — 仅当调用危险工具时审查</li>
 *   <li>keyword-match — 推理内容包含敏感关键词时审查</li>
 * </ul>
 *
 * <p>优先级: 70（在 ToolGateHook 之后执行）
 *
 * <p>V2.0 对应 Middleware: {@link io.yunxi.platform.agent.middleware.ReasoningReviewMiddleware}
 */
public class ReasoningReviewHook {

    private static final Logger log = LoggerFactory.getLogger(ReasoningReviewHook.class);

    /** 审查策略: all | on-dangerous-tool | keyword-match */
    private final String strategy;

    /** 敏感关键词列表（keyword-match 策略使用） */
    private final List<String> keywords;

    /** 危险工具名称集合（on-dangerous-tool 策略使用） */
    private final Set<String> dangerousTools;

    /** Hook 优先级 */
    static final int PRIORITY = 70;

    /**
     * 创建推理审查 Hook
     *
     * @param config         推理审查配置
     * @param dangerousTools 危险工具名称集合
     */
    public ReasoningReviewHook(ReasoningReviewConfig config, Set<String> dangerousTools) {
        this.strategy = config.getStrategy() != null ? config.getStrategy() : "on-dangerous-tool";
        this.keywords = config.getKeywords() != null ? config.getKeywords() : List.of();
        this.dangerousTools = dangerousTools != null ? dangerousTools : Set.of();
    }

    /**
     * 处理推理后事件，根据策略决定是否审查
     *
     * @param event 推理后事件
     * @return Mono
     */
    public Mono<Void> onEvent(PostReasoningEvent event) {
        return Mono.fromRunnable(() -> {
            Msg msg = event.getReasoningMessage();
            if (msg == null) {
                return;
            }

            boolean shouldReview = switch (strategy) {
                case "all" -> true;
                case "on-dangerous-tool" -> containsDangerousTool(msg);
                case "keyword-match" -> containsKeyword(msg);
                default -> false;
            };

            if (shouldReview) {
                log.info("推理审查策略 [{}] 触发，暂停 Agent 等待人工审查", strategy);
                event.stopAgent();
            }
        });
    }

    /**
     * 检查消息是否包含危险工具调用
     */
    private boolean containsDangerousTool(Msg msg) {
        List<?> blocks = msg.getContent();
        if (blocks == null) {
            return false;
        }
        return blocks.stream()
                .filter(block -> block instanceof ToolUseBlock)
                .map(block -> ((ToolUseBlock) block).getName())
                .anyMatch(dangerousTools::contains);
    }

    /**
     * 检查消息文本内容是否包含敏感关键词
     */
    private boolean containsKeyword(Msg msg) {
        List<?> blocks = msg.getContent();
        if (blocks == null) {
            return false;
        }
        String fullText = blocks.stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .reduce("", String::concat);
        return keywords.stream().anyMatch(fullText::contains);
    }

    /**
     * 返回 Hook 优先级
     *
     * @return 优先级值
     */
    public int priority() {
        return PRIORITY;
    }
}
