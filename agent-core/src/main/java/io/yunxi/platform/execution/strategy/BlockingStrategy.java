package io.yunxi.platform.execution.strategy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.agent.AgentConfigurer;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.ExecutionStrategy;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.tracing.LlmMetrics;

/**
 * 阻塞执行策略（非流式通道）。
 *
 * <p>阻塞通道的消息组装与收尾语义：</p>
 * <ul>
 *   <li>从 ExecutionContext 取拦截器已组装的 inputMessages（含历史/实体注入/RAG 注入）；</li>
 *   <li>streamEvents 阻塞收尾（收集 AGENT_RESULT → Msg），usage 记录；</li>
 *   <li>会话 ID：无会话入口时以 userId 充当 sessionId。</li>
 * </ul>
 *
 * <p>注：请求级文件 RAG 注入由 {@code RagRetrievalInterceptor}（order=300）
 * 在拦截器链完成，流式/阻塞两通道统一。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class BlockingStrategy implements ExecutionStrategy {

    private final LlmMetrics llmMetrics;
    private final AgentscopeCoreProperties properties;

    public BlockingStrategy(LlmMetrics llmMetrics,
                            AgentscopeCoreProperties properties) {
        this.llmMetrics = llmMetrics;
        this.properties = properties;
    }

    @Override
    public boolean supports(ExecutionContext ctx) {
        // 结构化输出（阻塞）由 StructuredBlockingStrategy 承接
        return !ctx.getRequest().isStreaming() && !ctx.getRequest().isStructuredOutput();
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

        // 会话 ID：无会话入口时以 userId 充当 sessionId
        String sessionId = ctx.getRequest().getConversationId() != null
                ? ctx.getRequest().getConversationId()
                : ctx.getRequest().getUserId();
        RuntimeContext rc = buildRuntimeContext(ctx.getRequest().getUserId(), sessionId);
        Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());

        // 会话级工具组激活：覆盖持久化/遗留空激活组，确保 MCP 工具在每次会话可用
        AgentConfigurer.activateSessionToolGroups((HarnessAgent) agent, rc.getUserId(), rc.getSessionId());

        // 注意：共享 Agent 实例生命周期由框架管理，绝不可 close（内联转换避免 JDT resource-leak 误报）
        final Msg[] holder = new Msg[1];
        ((HarnessAgent) agent).streamEvents(messages, rc)
                .timeout(timeout)
                .filter(event -> event.getType() == AgentEventType.AGENT_RESULT)
                .ofType(AgentResultEvent.class)
                .doOnNext(event -> holder[0] = event.getResult())
                .blockLast();

        // usage 记录：以原始 agentName 作维度
        if (holder[0] != null && holder[0].getUsage() != null) {
            llmMetrics.recordAndLogUsage(ctx.getAgentName(), "yunxi", holder[0].getUsage());
        }
        return holder[0];
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
