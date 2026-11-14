package io.yunxi.runtime;

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;

/**
 * 运行时平面。唯一的、协议无关的执行请求。每个入口适配器（web / A2A / AG-UI / Chat Completions）在抵达
 * {@link ExecutionFacade} 之前，必须把它原生的请求翻译为这一类型；传输侧不承载任何推理语义。
 */
public record AgentCommand(
        String agentId,
        String conversationId,
        String userId,
        String sessionId,
        List<Msg> messages,
        Map<String, Object> profile,
        Map<String, Object> contextData,
        Map<String, Object> confirmResults,
        String structuredOutput,
        String intent,
        String traceparent) {
}
