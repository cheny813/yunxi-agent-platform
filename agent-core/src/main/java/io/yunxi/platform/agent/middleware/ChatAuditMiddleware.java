package io.yunxi.platform.agent.middleware;

import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.platform.shared.entity.ChatLogEntity;
import io.yunxi.platform.shared.mapper.ConversationMapper;
import reactor.core.publisher.Flux;

/**
 * 对话审计中间件。
 *
 * <p>在每次调用结束时记录一条审计日志：请求标识、耗时、成败与错误摘要。耗时从调用开始计到
 * 事件流终结（完成或出错），覆盖模型推理与工具调用的全过程。</p>
 *
 * <p>是否记录由调用方按调用维度声明，见 {@link CallContextKeys#AUDIT_ENABLED_KEY}。
 * 落库是旁路行为：失败仅记录告警，不影响本次调用的事件流。</p>
 *
 * @author yunxi-agent-platform
 */
public class ChatAuditMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ChatAuditMiddleware.class);

    /** 错误信息落库的最大长度 */
    private static final int MAX_ERROR_LENGTH = 500;

    private final ConversationMapper conversationMapper;
    private final String agentName;

    public ChatAuditMiddleware(ConversationMapper conversationMapper, String agentName) {
        this.conversationMapper = conversationMapper;
        this.agentName = agentName;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (!CallContextKeys.auditEnabled(ctx)) {
            return next.apply(input);
        }
        String requestId = UUID.randomUUID().toString().replace("-", "");
        long startMs = System.currentTimeMillis();
        String userMessage = lastUserText(input);
        return next.apply(input)
                .doOnComplete(() -> record(ctx, requestId, startMs, userMessage, null))
                .doOnError(err -> record(ctx, requestId, startMs, userMessage,
                        err != null ? err.getMessage() : "执行失败"));
    }

    private void record(RuntimeContext ctx, String requestId, long startMs,
                        String userMessage, String errorMessage) {
        long durationMs = System.currentTimeMillis() - startMs;
        try {
            ChatLogEntity entity = new ChatLogEntity();
            entity.setConversationId(ctx != null ? ctx.getSessionId() : null);
            entity.setAgentName(agentName);
            entity.setUserId(ctx != null ? ctx.getUserId() : null);
            entity.setUserMessage(userMessage);
            entity.setDurationMs(durationMs);
            entity.setSuccess(errorMessage == null);
            entity.setErrorMessage(truncate(errorMessage));
            conversationMapper.insertChatLog(entity);
            if (log.isDebugEnabled()) {
                log.debug("审计落库: requestId={}, agent={}, success={}, durationMs={}",
                        requestId, agentName, errorMessage == null, durationMs);
            }
        } catch (Exception e) {
            log.warn("审计落库失败: requestId={}, agent={}: {}", requestId, agentName, e.getMessage());
        }
    }

    /**
     * 取输入消息中的最后一条非空文本，作为审计日志中的用户输入字段。
     */
    private static String lastUserText(AgentInput input) {
        if (input == null) {
            return null;
        }
        java.util.List<io.agentscope.core.message.Msg> msgs = input.msgs();
        if (msgs == null || msgs.isEmpty()) {
            return null;
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            io.agentscope.core.message.Msg msg = msgs.get(i);
            String text = msg != null ? msg.getTextContent() : null;
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }
}
