package io.yunxi.platform.execution;

import java.util.HashMap;
import java.util.Map;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.intent.IntentResult;
import io.yunxi.platform.intent.routing.RouteDecision;
import io.yunxi.platform.shared.entity.ConversationEntity;

/**
 * 单次执行上下文（拦截器共享可变状态）。
 *
 * <p>拦截器链（{@link ExecutionInterceptor}）通过 {@code preHandle} 写入本上下文，
 * 引擎在链执行完后统一从本上下文读取 {@code resolvedAgent}/{@code inputMessages}/{@code routeResult}
 * 等字段驱动后续执行。采用"改 ctx 共享状态"契约（非函数式返回）：
 * 拦截器就地重写 {@code resolvedAgent}/{@code inputMessages} 等字段。</p>
 *
 * <p>本对象每次请求新建，无并发问题。</p>
 *
 * @author yunxi-agent-platform
 */
public class ExecutionContext {

    /** 归一化请求（不可变引用，拦截器只读） */
    private final ExecutionRequest request;
    /** 会话 ID（conversationId，作为 AgentScope sessionId 实现多租户隔离） */
    private final String conversationId;
    /** 当前认证用户 ID（门面预解析；AuthResolve 拦截器可用 SecurityContext 兜底回写，可为 null/blank） */
    private String userId;
    /** 当前绑定的 Agent 名（来自 URL 路径，不可变） */
    private final String agentName;

    /** 记忆模式分支产出的输入消息（拦截器写入；默认单条原始 userMsg） */
    private Msg inputMessage;
    /** 记忆模式分支产出的完整历史消息（拦截器写入；QUICK/BLOCKING 可为空） */
    private java.util.List<Msg> inputMessages;
    /** 最终执行的 Agent（拦截器可改道写入） */
    private Agent resolvedAgent;
    /** 意图分析结果（IntentPipeline 拦截器写入，可为 null） */
    private IntentResult intentResult;
    /** 路由决策（IntentPipeline 拦截器写入，可为 null） */
    private RouteDecision routeDecision;
    /** 执行结果错误信息（引擎收口时写入；null 表示成功），供 postHandle 收尾（如审计）消费 */
    private String executionError;
    /** 会话实体（引擎从 ConversationDomainService 加载后写入；非会话型入口为 null） */
    private ConversationEntity conversation;
    /** 运行时透传的附加属性（如 RAG 注入标记、场景名等） */
    private final Map<String, Object> attributes = new HashMap<>();

    public ExecutionContext(ExecutionRequest request, String conversationId, String userId, String agentName) {
        this.request = request;
        this.conversationId = conversationId;
        this.userId = userId;
        this.agentName = agentName;
    }

    public ExecutionRequest getRequest() {
        return request;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    /**
     * 回写用户 ID（AuthResolve 拦截器 SecurityContext 兜底时调用，供下游拦截器/策略/审计消费）。
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentName() {
        return agentName;
    }

    public Msg getInputMessage() {
        return inputMessage;
    }

    public void setInputMessage(Msg inputMessage) {
        this.inputMessage = inputMessage;
    }

    public java.util.List<Msg> getInputMessages() {
        return inputMessages;
    }

    public void setInputMessages(java.util.List<Msg> inputMessages) {
        this.inputMessages = inputMessages;
    }

    public Agent getResolvedAgent() {
        return resolvedAgent;
    }

    public void setResolvedAgent(Agent resolvedAgent) {
        this.resolvedAgent = resolvedAgent;
    }

    public IntentResult getIntentResult() {
        return intentResult;
    }

    public void setIntentResult(IntentResult intentResult) {
        this.intentResult = intentResult;
    }

    public RouteDecision getRouteDecision() {
        return routeDecision;
    }

    public void setRouteDecision(RouteDecision routeDecision) {
        this.routeDecision = routeDecision;
    }

    public String getExecutionError() {
        return executionError;
    }

    public void setExecutionError(String executionError) {
        this.executionError = executionError;
    }

    public ConversationEntity getConversation() {
        return conversation;
    }

    public void setConversation(ConversationEntity conversation) {
        this.conversation = conversation;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
}
