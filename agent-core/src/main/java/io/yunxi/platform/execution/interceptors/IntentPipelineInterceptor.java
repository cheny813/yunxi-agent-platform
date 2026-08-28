package io.yunxi.platform.execution.interceptors;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.ExecutionInterceptor;
import io.yunxi.platform.intent.IntentContext;
import io.yunxi.platform.intent.IntentEngine;
import io.yunxi.platform.intent.IntentResult;
import io.yunxi.platform.intent.routing.IntentAwareAgentResolver;
import io.yunxi.platform.intent.routing.RouteDecision;

/**
 * 拦截器 200：意图管道。
 *
 * <p>职责：用 {@code intentEngine.analyze(new IntentContext(...))} 做意图/实体/场景分析，
 * 再用 {@code resolver.resolve(agentName, profile, userId, intentResult)} 路由改道。
 * 命中改道时把 {@code ctx.resolvedAgent} 重写为目标 Agent，并把实体/改写 query 注入 userMsg 文本。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class IntentPipelineInterceptor implements ExecutionInterceptor {

    private final IntentEngine intentEngine;
    private final IntentAwareAgentResolver resolver;

    public IntentPipelineInterceptor(IntentEngine intentEngine, IntentAwareAgentResolver resolver) {
        this.intentEngine = intentEngine;
        this.resolver = resolver;
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void preHandle(ExecutionContext ctx) {
        // QUICK 模式与无会话入口跳过意图管道（快速/无会话入口不含意图路由）。
        // 结构化输出同样跳过：Schema 按 agentName 注册（SchemaClassRegistry），
        // 意图改道会导致 Schema 与目标 Agent 错配，保持直连语义。
        if (ctx.getRequest().isQuickMode() || ctx.getConversationId() == null
                || ctx.getRequest().isStructuredOutput()) {
            return;
        }
        String message = ctx.getRequest().getMessage();
        String agentName = ctx.getAgentName();
        String userId = ctx.getUserId();
        String conversationId = ctx.getConversationId();
        String profile = ctx.getRequest().getProfile();
        // 只取最近 3 轮会话消息做指代消解
        List<Msg> recent = tail(ctx.getRequest().getHistoryMessages(), 3);

        IntentContext intentContext = new IntentContext(
                message,
                agentName,
                userId,
                conversationId,
                recent,
                null,
                profile);

        IntentResult intentResult = intentEngine.analyze(intentContext);
        ctx.setIntentResult(intentResult);

        RouteDecision decision = resolver.resolve(agentName, profile, userId, intentResult);
        ctx.setRouteDecision(decision);

        if (decision != null && decision.adopted() && decision.agent() != null) {
            ctx.setResolvedAgent(decision.agent());
        }

        // 实体注入：把 NER 实体拼回 userMsg，供下游记忆/路由一致使用
        // 注入格式：[问题实体] 块，type: value
        Msg current = ctx.getInputMessage();
        if (current != null && intentResult.entities() != null && !intentResult.entities().isEmpty()) {
            String block = intentResult.entities().stream()
                    .map(e -> "- " + e.type() + ": " + e.value())
                    .collect(Collectors.joining("\n"));
            String enriched = current.getTextContent()
                    + "\n\n[问题实体]\n注意：以下实体已从问题中识别，AI 应直接使用。\n" + block;
            Msg enrichedMsg = Msg.builder()
                    .textContent(enriched)
                    .role(MsgRole.USER)
                    .metadata(current.getMetadata())
                    .build();
            ctx.setInputMessage(enrichedMsg);
            if (ctx.getInputMessages() != null && !ctx.getInputMessages().isEmpty()) {
                ctx.getInputMessages().set(ctx.getInputMessages().size() - 1, enrichedMsg);
            }
        }
    }

    /**
     * 取最近 {@code n} 条消息。
     */
    private static List<Msg> tail(List<Msg> all, int n) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (all.size() <= n) {
            return new ArrayList<>(all);
        }
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }
}
