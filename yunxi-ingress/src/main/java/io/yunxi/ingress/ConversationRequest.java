package io.yunxi.ingress;

import java.util.HashMap;
import java.util.Map;

/**
 * 入口织面。协议无关的会话请求。所有外部协议（web / A2A / AG-UI / Chat Completions）在进入运行时前，
 * 都必须先被翻译为这一类型；传输层不承载任何推理语义。
 */
public class ConversationRequest {

    /** 来源协议标识：web / a2a / agui / chat-completions。 */
    private String protocol;

    /** 目标 Agent 的唯一 id（缺省时由意图引擎推断回填）。 */
    private String agentId;

    /** 发起请求的用户 id（由上游身份解析归一后透传）。 */
    private String userId;

    /** 会话 id（同一会话内保持轮次上下文）。 */
    private String sessionId;

    /** 本轮用户输入文本。 */
    private String text;

    /** 解析后的租户 id（由入站适配器在 admit 段经 {@code TenantResolver} 解析并写入；未启用多租户时为 null）。 */
    private String tenantId;

    /** 协议附带的业务上下文（如 HITL 确认结果、结构化输出声明等）。 */
    private Map<String, Object> context = new HashMap<>();

    public ConversationRequest() {
    }

    public ConversationRequest(String protocol, String agentId, String userId, String sessionId, String text) {
        this.protocol = protocol;
        this.agentId = agentId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.text = text;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context != null ? context : new HashMap<>();
    }
}
