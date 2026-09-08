package io.yunxi.platform.execution.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.agent.AgentConfigurer;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.execution.spi.ExecutionStrategy;

/**
 * 流式执行策略（流式通道）。
 *
 * <p>流式通道的消息组装与调用语义：</p>
 * <ul>
 *   <li>从 ExecutionContext 取拦截器已组装的 inputMessages；</li>
 *   <li>计算思考事件文本（仅会话 + 非 quick + (useA2A || enableThinking)
 *       时生成，写入 {@code thinkingText} 属性供引擎编排 start/thinking 事件；
 *       无会话/quick 为 null）；</li>
 *   <li>直接返回 {@code streamEvents} 事件流，由引擎统一过算子链 + 适配器。</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 */
@Component
public class StreamingStrategy implements ExecutionStrategy {

    /** 思考事件文本在 ExecutionContext.attributes 中的键 */
    public static final String ATTR_THINKING_TEXT = "thinkingText";

    @Override
    public boolean supports(ExecutionContext ctx) {
        return ctx.getRequest().isStreaming();
    }

    @Override
    public Object execute(ExecutionContext ctx) {
        Agent agent = ctx.getResolvedAgent();
        if (agent == null) {
            throw new IllegalStateException("Agent 未解析");
        }

        List<Msg> messages = ctx.getInputMessages();
        if (messages == null || messages.isEmpty()) {
            messages = new ArrayList<>();
            if (ctx.getInputMessage() != null) {
                messages.add(ctx.getInputMessage());
            }
        }

        RuntimeContext rc = buildRuntimeContext(ctx.getRequest().getUserId(),
                ctx.getRequest().getConversationId());

        // 思考事件文本（生成规则见 computeThinkingText）
        String thinkingText = computeThinkingText(ctx, messages.size());
        ctx.setAttribute(ATTR_THINKING_TEXT, thinkingText);

        // 会话级工具组激活：覆盖持久化/遗留空激活组，确保 MCP 工具在每次会话可用
        AgentConfigurer.activateSessionToolGroups((HarnessAgent) agent, rc.getUserId(), rc.getSessionId());

        // 注意：共享 Agent 实例生命周期由框架管理，绝不可 close（内联转换避免 JDT resource-leak 误报）
        return ((HarnessAgent) agent).streamEvents(messages, rc);
    }

    /**
     * 计算思考事件文本。
     *
     * <p>quick 分支与无会话入口传 null；标准分支在 useA2A || enableThinking
     * 时生成，A2A 模式展示上下文条数。</p>
     */
    private String computeThinkingText(ExecutionContext ctx, int contextSize) {
        ExecutionRequest request = ctx.getRequest();
        if (request.getConversationId() == null || request.isQuickMode()) {
            return null;
        }
        boolean useA2A = request.isUseA2A() || request.isDeepMode();
        if (!useA2A && !request.isEnableThinking()) {
            return null;
        }
        String userQuestion = request.getMessage();
        if (userQuestion != null && userQuestion.length() > 80) {
            userQuestion = userQuestion.substring(0, 80) + "...";
        }
        if (useA2A) {
            return String.format("分析需求: %s\n正在协调专家智能体处理 (%d 条上下文)..",
                    userQuestion, contextSize);
        }
        return String.format("分析: %s", userQuestion);
    }

    private static RuntimeContext buildRuntimeContext(String userId, String sessionId) {
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (userId != null && !userId.isBlank()) {
            b.userId(userId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            b.sessionId(sessionId);
        }
        return b.build();
    }
}
