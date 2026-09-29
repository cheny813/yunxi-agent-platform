package io.yunxi.platform.ingress.a2a.dto;

import java.util.Map;

/**
 * A2A 协议调用请求。
 *
 * <p>{@code context} 与 {@code options} 为调用方传入的额外上下文，由统一接入网关收敛进
 * 执行引擎的单一 {@code contextData} 通道（不在引擎侧另立上下文概念）。</p>
 */
public record InvokeRequest(
        String agentName,
        String message,
        String conversationId,
        Map<String, Object> context,
        Map<String, Object> options) {
}
