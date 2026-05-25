package io.yunxi.platform.framework.hitl;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
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
 * <p>在 LLM 推理完成后拦截推理结果，等待人类审查后再决定是否执行。
 * 支持三种策略：
 * <ul>
 *   <li><b>all</b> — 每次推理都需要人类审查</li>
 *   <li><b>on-dangerous-tool</b> — 仅在 LLM 决定调用危险工具时需要审查</li>
 *   <li><b>keyword-match</b> — 推理内容包含敏感关键词时需要审查</li>
 * </ul>
 *
 * <p>人类审查后可以：
 * <ul>
 *   <li>批准推理结果 — Agent 继续执行工具</li>
 *   <li>拒绝并提供反馈 — Agent 重新推理（通过注入反馈消息到对话历史）</li>
 * </ul>
 *
 * <p>执行顺序在 ToolGateHook 之后，确保先完成工具门控检查再做推理审查。
 *
 * @author yunxi-platform
 */
public class ReasoningReviewHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ReasoningReviewHook.class);

    /** 审查策略 */
    private final String strategy;

    /** on-dangerous-tool 策略下的危险工具集合 */
    private final Set<String> dangerousTools;

    /** keyword-match 策略下的敏感关键词集合 */
    private final Set<String> keywords;

    /**
     * 创建 ReasoningReviewHook
     *
     * @param config           推理审查配置
     * @param dangerousTools   ToolGateConfig 中的危险工具集合，用于 on-dangerous-tool 策略
     */
    public ReasoningReviewHook(ReasoningReviewConfig config, Set<String> dangerousTools) {
        this.strategy = config.getStrategy() != null ? config.getStrategy() : "on-dangerous-tool";
        this.dangerousTools = dangerousTools != null ? dangerousTools : Set.of();
        this.keywords = config.getKeywords() != null ? Set.copyOf(config.getKeywords()) : Set.of();
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent post) {
            return handlePostReasoning(post).thenReturn(event);
        }
        if (event instanceof PostActingEvent post) {
            return handlePostActing(post).thenReturn(event);
        }
        return Mono.just(event);
    }

    /**
     * 处理 PostReasoningEvent — 审查 LLM 的推理结果
     */
    private Mono<Void> handlePostReasoning(PostReasoningEvent event) {
        Msg reasoningMsg = event.getReasoningMessage();
        if (reasoningMsg == null) {
            return Mono.empty();
        }

        // 根据策略判断是否需要审查
        if (!needsReview(reasoningMsg)) {
            return Mono.empty();
        }

        String reviewReason = getReviewReason(reasoningMsg);
        log.warn("推理需要人工审查: {}", reviewReason);

        // 注入审查提示到推理消息
        event.appendSystemContent("[审查] " + reviewReason + " — 请审查上述推理结果，"
                + "批准后将执行工具，拒绝可提供修改建议。");

        // 暂停 Agent，等待人类审查
        event.stopAgent();

        return Mono.empty();
    }

    /**
     * 处理 PostActingEvent — 审查工具执行结果（非阻断式审计）
     */
    private Mono<Void> handlePostActing(PostActingEvent event) {
        // 可选：对工具执行结果做日志审计
        // 当前非阻断，仅记录
        return Mono.empty();
    }

    /**
     * 判断当前推理是否需要审查
     */
    private boolean needsReview(Msg reasoningMsg) {
        return switch (strategy) {
            case "all" -> true;
            case "on-dangerous-tool" -> hasDangerousTool(reasoningMsg);
            case "keyword-match" -> hasMatchingKeyword(reasoningMsg);
            default -> {
                log.warn("未知的审查策略: {}，默认不审查", strategy);
                yield false;
            }
        };
    }

    /**
     * 检测推理消息中是否包含危险工具调用
     */
    private boolean hasDangerousTool(Msg reasoningMsg) {
        List<ToolUseBlock> toolCalls = reasoningMsg.getContentBlocks(ToolUseBlock.class);
        if (toolCalls == null || toolCalls.isEmpty()) {
            return false;
        }
        return toolCalls.stream().anyMatch(tc -> dangerousTools.contains(tc.getName()));
    }

    /**
     * 检测推理消息文本中是否包含敏感关键词
     */
    private boolean hasMatchingKeyword(Msg reasoningMsg) {
        String text = reasoningMsg.getTextContent();
        if (text == null || keywords.isEmpty()) {
            return false;
        }
        String textLower = text.toLowerCase();
        return keywords.stream().anyMatch(kw -> textLower.contains(kw.toLowerCase()));
    }

    /**
     * 获取审查原因描述
     */
    private String getReviewReason(Msg reasoningMsg) {
        return switch (strategy) {
            case "all" -> "每次推理均需人工审查";
            case "on-dangerous-tool" -> "LLM 决定调用危险工具需要审查";
            case "keyword-match" -> "推理内容涉及敏感关键词需要审查";
            default -> "推理需要人工审查";
        };
    }

    @Override
    public int priority() {
        // 在 ToolGateHook(priority=55) 之后执行
        return 70;
    }
}