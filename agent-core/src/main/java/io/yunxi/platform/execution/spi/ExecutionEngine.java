package io.yunxi.platform.execution.spi;

import io.agentscope.core.event.AgentEvent;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.execution.ExecutionResult;
import io.yunxi.platform.shared.entity.ConversationEntity;
import reactor.core.publisher.Flux;

/**
 * 统一执行引擎抽象（契约接口，驻留 agent-core，避免 agent-core 反向依赖 agent-execution）。
 *
 * <p>门面层（ChatAppService / A2AServer）与入口网关只依赖本接口与 {@link ExecutionRequest}/
 * {@link ExecutionResult} 等数据契约，具体实现 {@code AgentExecutionEngine} 位于独立模块
 * agent-execution 并经 Spring 注入。这样 agent-core / agent-ingress 不需要编译期依赖
 * agent-execution，从根上断开循环依赖。</p>
 *
 * @author yunxi-agent-platform
 */
public interface ExecutionEngine {

    /**
     * 执行一次对话（阻塞 Msg / 流式 SSE 流 / 错误）。
     *
     * @param request      归一化执行请求（已含 agentName/conversationId/userId/streaming）
     * @param conversation 会话实体（可为 null，非会话型入口）
     * @return 执行结果
     */
    ExecutionResult execute(ExecutionRequest request, ConversationEntity conversation);

    /**
     * 执行并返回原始 AgentEvent 流（不经协议适配器 / 轨迹投影）。
     *
     * @param request      归一化执行请求（须 streaming=true）
     * @param conversation 会话实体（可为 null，非会话型入口）
     * @return 原始 AgentEvent 流
     */
    Flux<AgentEvent> executeEvents(ExecutionRequest request, ConversationEntity conversation);

    /**
     * 格式化 Agent 调用异常为用户友好消息（门面层 catch 兜底复用）。
     */
    static String formatAgentError(Throwable e) {
        if (e instanceof IllegalArgumentException) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("different type of Path")) {
                return "服务内部错误，请联系管理员";
            }
        }
        String message = e.getMessage();
        return message != null ? message : "未知错误";
    }
}
