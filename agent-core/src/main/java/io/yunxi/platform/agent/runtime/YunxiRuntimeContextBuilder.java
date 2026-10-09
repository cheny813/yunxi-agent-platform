package io.yunxi.platform.agent.runtime;

import java.util.List;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.agent.middleware.CallContextKeys;
import io.yunxi.platform.trace.TraceContextKeys;

/**
 * RuntimeContext 唯一权威构造器（全平台单一来源）。
 *
 * <p>所有协议入口（web/A2A、AG-UI、Chat Completions、aistio）构造 RuntimeContext 时必须经本类，
 * 不得在各入口自行 {@code RuntimeContext.builder()} 后零散注入 userId / 会话标识 / 调用级上下文，
 * 否则会重演「同一身份概念多份构造、部分协议漏注入」的重复构造问题。
 *
 * <p>本类不承载任何协议语义，只消费框架原生入参（userId / sessionId / 审计开关 / 历史消息 /
 * 文件检索词 / 意图轨迹节点），因此能被所有协议复用；协议专属元数据由各入口自行取数后作为入参传入。</p>
 */
public final class YunxiRuntimeContextBuilder {

    private YunxiRuntimeContextBuilder() {
    }

    /**
     * 构造运行时上下文（完整入参）。
     *
     * @param userId           用户标识（空白时不注入）
     * @param sessionId        会话标识（空白时忽略）
     * @param auditEnabled     是否记录本次调用审计
     * @param historyMessages  前置注入的历史消息（null/空不注入）
     * @param fileContextQuery 文件检索词（空白时不检索）
     * @param intentSpanData   意图轨迹节点（null 不注入）
     * @return 构造完成的运行时上下文
     */
    public static RuntimeContext build(
            String userId,
            String sessionId,
            boolean auditEnabled,
            List<Msg> historyMessages,
            String fileContextQuery,
            TraceContextKeys.IntentSpanData intentSpanData) {
        RuntimeContext.Builder builder = RuntimeContext.builder();
        RuntimeContextIdentityEnricher.applyUserId(builder, userId);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.sessionId(sessionId);
        }
        CallContextKeys.apply(builder, historyMessages, fileContextQuery, auditEnabled);
        if (intentSpanData != null) {
            builder.put(TraceContextKeys.INTENT_SPAN_KEY, intentSpanData);
        }
        return builder.build();
    }

    /**
     * 构造最小化运行时上下文（仅身份与会话标识），用于状态读取等无需调用级上下文的场景。
     *
     * @param userId    用户标识（空白时不注入）
     * @param sessionId 会话标识（空白时忽略）
     * @return 构造完成的运行时上下文
     */
    public static RuntimeContext build(String userId, String sessionId) {
        return build(userId, sessionId, false, null, null, null);
    }
}
