package io.yunxi.ingress;

import io.yunxi.runtime.Settle;

/**
 * 入口织面。协议无关的会话响应。由运行时 {@link Settle} 与事件流最终文本组装而成，供各协议适配器回写为原生响应。
 */
public class ConversationResponse {

    /** 产生本响应的 Agent id。 */
    private String agentId;

    /** 会话 id。 */
    private String sessionId;

    /** 本轮助手回复文本（从事件流末端的 AgentResultEvent 提取）。 */
    private String text;

    /** 执行结算状态（OK / 各类异常）。 */
    private Settle.Status status;

    /** 结算错误码（状态非 OK 时携带，供调用方定位）。 */
    private String errorCode;

    /** 根因描述（状态非 OK 时携带，透传底层执行异常的根因消息，供调用方与排障定位）。 */
    private String description;

    public ConversationResponse() {
    }

    public ConversationResponse(String agentId, String sessionId, String text, Settle.Status status, String errorCode) {
        this(agentId, sessionId, text, status, errorCode, null);
    }

    public ConversationResponse(String agentId, String sessionId, String text, Settle.Status status,
            String errorCode, String description) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.text = text;
        this.status = status;
        this.errorCode = errorCode;
        this.description = description;
    }

    /** 由结算终态构造协议响应：集中承载 status/errorCode/description 的映射，非流式与流式错误边界共用。 */
    public static ConversationResponse from(Settle settle, String text) {
        return new ConversationResponse(settle.agentId(), settle.sessionId(), text,
                settle.status(), settle.errorCode(), settle.description());
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Settle.Status getStatus() {
        return status;
    }

    public void setStatus(Settle.Status status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
