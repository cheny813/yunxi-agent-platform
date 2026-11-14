package io.yunxi.runtime.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.observability.AuditEvent;
import io.yunxi.observability.AuditSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 对话审计中间件（order=10，位于洋葱链最外层）。
 *
 * <p>职责：把每一次 Agent 调用与最终系统提示词落结构化审计，经统一审计汇点 {@link AuditSink} 输出。
 * 它只记录、绝不携带推理语义，且对所有协议（web / A2A / AG-UI / Chat Completions）统一生效，
 * 因此留在产品平面、不进入框架运行时。
 *
 * <p>四个横切中间件之一，用于闭环「对话审计缺失」这一内核缺口：协议端点默认无鉴权与审计，
 * 需由产品平面统一叠加，而非协议内联。
 */
public class ChatAuditMiddleware implements MiddlewareBase {

    private final AuditSink auditSink;

    public ChatAuditMiddleware(AuditSink auditSink) {
        this.auditSink = auditSink;
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
        String userId = ctx == null ? "" : orEmpty(ctx.getUserId());
        String sessionId = ctx == null ? "" : orEmpty(ctx.getSessionId());
        String agentId = agent.getName();
        String userText = firstUserText(input.msgs());
        auditSink.emit(new AuditEvent(
                "agent.invoke",
                userId,
                Instant.now(),
                Map.of(
                        "agentId", agentId,
                        "sessionId", sessionId,
                        "userId", userId,
                        "userText", userText)));
        return next.apply(input);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        int length = currentPrompt == null ? 0 : currentPrompt.length();
        auditSink.emit(new AuditEvent(
                "system.prompt",
                ctx == null ? "" : orEmpty(ctx.getUserId()),
                Instant.now(),
                Map.of("agentId", agent.getName(), "length", String.valueOf(length))));
        return Mono.just(currentPrompt);
    }

    private static String firstUserText(List<Msg> msgs) {
        if (msgs == null) {
            return "";
        }
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return "";
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
