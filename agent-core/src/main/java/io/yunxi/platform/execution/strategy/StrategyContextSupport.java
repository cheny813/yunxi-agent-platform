package io.yunxi.platform.execution.strategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.agent.middleware.CallContextKeys;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.execution.interceptors.RagRetrievalInterceptor;
import io.yunxi.platform.intent.IntentResult;
import io.yunxi.platform.intent.routing.RouteDecision;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.trace.TraceCollectorMiddleware;

/**
 * 执行策略共用的运行时上下文构造支持。
 *
 * <p>三种执行策略（流式、阻塞、结构化阻塞）都需要把本次调用的输入处理参数写入框架的
 * 运行时上下文，供请求级中间件读取。装配期无法获知这些参数，因此统一在此处按调用解析。</p>
 *
 * @author yunxi-agent-platform
 */
final class StrategyContextSupport {

    private StrategyContextSupport() {
    }

    /**
     * 构造本次调用的运行时上下文。
     *
     * @param ctx          执行上下文
     * @param sessionId    会话标识（各策略的取值方式不同，由调用方给出）
     * @param auditEnabled 审计开关
     * @return 运行时上下文
     */
    static RuntimeContext build(ExecutionContext ctx, String sessionId, boolean auditEnabled) {
        ExecutionRequest request = ctx.getRequest();
        String userId = request.getUserId();
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (userId != null && !userId.isBlank()) {
            b.userId(userId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            b.sessionId(sessionId);
        }
        CallContextKeys.apply(b, resolveHistory(ctx), resolveRagQuery(ctx), auditEnabled);
        applyIntentSpan(b, ctx);
        RuntimeContext rc = b.build();
        // 把本次调用的运行时上下文回写执行上下文，供统一执行引擎在投影阶段读取
        // （意图注入、任务清单旁路读取都依赖它）。
        ctx.setAttribute("yunxi.runtimeContext", rc);
        return rc;
    }

    /**
     * 把本次调用的意图分析产物写入运行时上下文，供归集中间件注入为轨迹节点。
     *
     * <p>意图分析发生在调用<b>之前</b>（请求解析阶段），而归集发生在调用<b>期间</b> ——
     * 两者时序不重叠，无法直接传递。运行时上下文是这两段之间唯一的通道。</p>
     *
     * <p>载荷只装用户可读的语义：原始 / 改写 query、实体、意图标签、场景、领域、
     * 路由决策与降级标记。刻意不放模型原始输出与内部计数 —— 轨迹是给人看的，
     * 塞入未加工内容只会让它变成日志。</p>
     */
    private static void applyIntentSpan(RuntimeContext.Builder b, ExecutionContext ctx) {
        IntentResult intent = ctx.getIntentResult();
        if (intent == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("originalQuery", intent.originalQuery());
        payload.put("rewrittenQuery", intent.rewrittenQuery());
        payload.put("sceneName", intent.sceneName());
        payload.put("domain", intent.domain());
        payload.put("degraded", intent.degraded());
        if (intent.intent() != null) {
            payload.put("intentLabel", intent.intent().label());
            payload.put("intentId", intent.intent().intentId());
            payload.put("confidence", intent.intent().confidence());
            payload.put("matchedBy", intent.intent().matchedBy());
        }
        if (intent.entities() != null && !intent.entities().isEmpty()) {
            payload.put("entities", intent.entities().stream()
                    .map(e -> Map.of("type", e.type(), "value", e.value()))
                    .toList());
        }
        RouteDecision decision = ctx.getRouteDecision();
        if (decision != null) {
            payload.put("routeAdopted", decision.adopted());
            if (decision.agent() != null) {
                payload.put("routedTo", decision.agent().getName());
            }
            if (decision.experts() != null && !decision.experts().isEmpty()) {
                payload.put("experts", decision.experts());
            }
        }
        // 改写后的 query 与原始不同时单独标出，便于回答「意图改写影响了什么」
        if (intent.rewrittenQuery() != null
                && !intent.rewrittenQuery().equals(intent.originalQuery())) {
            payload.put("queryRewritten", true);
        }
        b.put(TraceCollectorMiddleware.INTENT_SPAN_KEY,
                new TraceCollectorMiddleware.IntentSpanData(payload, ctx.getIntentDurationMs()));
    }

    /**
     * 解析本次调用需要前置注入的历史消息。
     *
     * <p>快速模式不注入历史；记忆模式为关闭或未要求包含历史时同样不注入。</p>
     */
    static List<Msg> resolveHistory(ExecutionContext ctx) {
        ExecutionRequest request = ctx.getRequest();
        if (request.isQuickMode()) {
            return List.of();
        }
        MemoryConfig memoryConfig = request.getMemoryConfig();
        if (memoryConfig == null || memoryConfig.isNone() || !request.isIncludeHistory()) {
            return List.of();
        }
        List<Msg> history = request.getHistoryMessages();
        return history != null ? history : List.of();
    }

    /**
     * 解析文件检索词。
     *
     * <p>检索词由启用条件拦截器判定后写入执行上下文属性；未写入表示本次不检索。</p>
     */
    static String resolveRagQuery(ExecutionContext ctx) {
        Object value = ctx.getAttributes().get(RagRetrievalInterceptor.ATTR_RAG_QUERY);
        return value instanceof String query && !query.isBlank() ? query : null;
    }

}
