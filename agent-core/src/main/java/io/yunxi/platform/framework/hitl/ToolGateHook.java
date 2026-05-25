package io.yunxi.platform.framework.hitl;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.yunxi.platform.shared.config.ToolGateConfig;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 工具门控 Hook
 *
 * <p>在 LLM 推理完成后、工具执行之前拦截，检测 LLM 是否决定调用配置的危险工具。
 * 如果检测到危险工具，调用 {@link PostReasoningEvent#stopAgent()} 暂停 Agent，
 * 等待人类审批。人类批准后 Agent 恢复执行，拒绝后前端注入拒绝消息让 LLM 重新规划。
 *
 * <p>需要注意执行顺序：
 * <ol>
 *   <li>{@link io.yunxi.platform.framework.hook.TextToolCallParserHook} (priority=45)
 *       — 先解析文本中的工具调用为 ToolUseBlock</li>
 *   <li><b>ToolGateHook</b> (priority=55) — 再检测危险工具</li>
 * </ol>
 *
 * <p>参考 AgentScope SDK 示例 {@code ToolConfirmationHook} 的实现模式。
 *
 * @author yunxi-platform
 */
public class ToolGateHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolGateHook.class);

    /** 需要审批的危险工具名称集合 */
    private final Set<String> dangerousTools;

    /** 审批提示模板 */
    private final String messageTemplate;

    /**
     * 创建 ToolGateHook
     *
     * @param config 工具门控配置
     */
    public ToolGateHook(ToolGateConfig config) {
        this.dangerousTools = Set.copyOf(config.getTools());
        this.messageTemplate = config.getMessage() != null ? config.getMessage()
                : "工具 [{tool}] 需要人工确认后才能执行";
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent post) {
            return handlePostReasoning(post).thenReturn(event);
        }
        return Mono.just(event);
    }

    private Mono<Void> handlePostReasoning(PostReasoningEvent event) {
        Msg reasoningMsg = event.getReasoningMessage();
        if (reasoningMsg == null) {
            return Mono.empty();
        }

        // 提取当前推理消息中的工具调用
        List<ToolUseBlock> toolCalls = reasoningMsg.getContentBlocks(ToolUseBlock.class);
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.empty();
        }

        // 检测是否有危险工具调用
        boolean hasDangerousTool = toolCalls.stream()
                .anyMatch(tc -> dangerousTools.contains(tc.getName()));

        if (!hasDangerousTool) {
            return Mono.empty();
        }

        // 记录日志并暂停 Agent
        List<String> matchedTools = toolCalls.stream()
                .filter(tc -> dangerousTools.contains(tc.getName()))
                .map(ToolUseBlock::getName)
                .toList();

        log.warn("检测到危险工具调用: {}，暂停 Agent 等待人工审批", matchedTools);

        // 通过系统消息注入审批提示，供前端展示
        event.appendSystemContent("审批提示: " + buildApprovalMessage(matchedTools));

        // 暂停 Agent，等待人类决策
        event.stopAgent();

        return Mono.empty();
    }

    /**
     * 构建审批提示消息
     */
    private String buildApprovalMessage(List<String> toolNames) {
        if (toolNames.size() == 1) {
            return messageTemplate.replace("{tool}", toolNames.get(0));
        }
        return "以下工具需要人工确认后才能执行: " + String.join(", ", toolNames);
    }

    @Override
    public int priority() {
        // 在 TextToolCallParserHook(priority=45) 之后执行，
        // 因为需要解析后的 ToolUseBlock 来判断
        return 55;
    }
}