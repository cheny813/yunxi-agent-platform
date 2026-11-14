package io.yunxi.ingress;

import io.yunxi.runtime.AgentCommand;
import io.yunxi.runtime.Settle;
import java.util.List;
import java.util.Map;

/**
 * 入口织面。协议适配器的公共基类。提供协议无关的 {@link AgentCommand} 组装逻辑，
 * 子类只需声明自身协议标识与少量差异字段。
 */
public abstract class AbstractProtocolAdapter implements ProtocolAdapter {

    /** 按协议标识大小写不敏感判断是否支持该协议。 */
    @Override
    public boolean supports(String protocol) {
        return protocol() != null && protocol().equalsIgnoreCase(protocol);
    }

    @Override
    public AgentCommand toCommand(ConversationRequest request) {
        // 入口织面只拼装协议无关的执行请求：文本转为单条用户消息，身份与上下文透传；
        // 意图标签（由网关解析后写入上下文）透传到命令的 intent 字段，供模型路由 / 观测消费。
        return new AgentCommand(
                request.getAgentId(),
                request.getSessionId(),
                request.getUserId(),
                request.getSessionId(),
                List.of(new io.agentscope.core.message.UserMessage(request.getText())),
                Map.of(),
                request.getContext(),
                Map.of(),
                null,
                intentLabelOf(request),
                null);
    }

    /** 取意图标签；未指定 Agent 的请求由网关解析后写入上下文，显式指定时此处为 null。 */
    private String intentLabelOf(ConversationRequest request) {
        Object label = request.getContext().get("yunxi.intent.label");
        return label instanceof String s ? s : null;
    }

    @Override
    public ConversationResponse toResponse(Settle settle, String text) {
        return ConversationResponse.from(settle, text);
    }
}
