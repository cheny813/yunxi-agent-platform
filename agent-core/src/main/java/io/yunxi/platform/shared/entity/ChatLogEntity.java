package io.yunxi.platform.shared.entity;

/**
 * 会话审计日志实体（对应 agent_chat_logs 表）。
 *
 * <p>由 AuditInterceptor（order=500）在请求收尾时写入：
 * pre 记录开始时间与请求标识，post 汇总耗时/结果落库。</p>
 *
 * @author yunxi-agent-platform
 */
public class ChatLogEntity {

    /** 会话 ID（可为 null，非会话型入口） */
    private String conversationId;

    /** Agent 名称 */
    private String agentName;

    /** 用户 ID */
    private String userId;

    /** 用户消息（审计摘要） */
    private String userMessage;

    /** Agent 响应（预留；流式通道不可得时为 null） */
    private String agentResponse;

    /** 使用的模型（预留） */
    private String modelUsed;

    /** 响应耗时（毫秒） */
    private Long durationMs;

    /** 是否成功 */
    private Boolean success;

    /** 错误信息（失败时） */
    private String errorMessage;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getAgentResponse() {
        return agentResponse;
    }

    public void setAgentResponse(String agentResponse) {
        this.agentResponse = agentResponse;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(String modelUsed) {
        this.modelUsed = modelUsed;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
