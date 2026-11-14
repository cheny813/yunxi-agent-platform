package io.yunxi.ingress;

import io.yunxi.runtime.AgentCommand;
import io.yunxi.runtime.Settle;

/**
 * 入口织面。协议适配器。把某个具体协议的请求翻译为运行时 {@link AgentCommand}，
 * 并把 {@link Settle} 与回复文本回写为该协议的 {@link ConversationResponse}。
 *
 * <p>所有适配器共享同一套运行时契约，协议差异只体现在请求/响应的外包装上，
 * 不渗透任何推理语义，复用框架能力而非重复实现。
 */
public interface ProtocolAdapter {

    /** 本适配器负责的协议标识。 */
    String protocol();

    /** 是否可处理该协议。 */
    boolean supports(String protocol);

    /** 把协议请求翻译为运行时命令。 */
    AgentCommand toCommand(ConversationRequest request);

    /** 把运行时结算结果与回复文本翻译为协议响应。 */
    ConversationResponse toResponse(Settle settle, String text);
}
