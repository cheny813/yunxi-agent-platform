package io.yunxi.platform.execution.spi;

import java.util.function.BooleanSupplier;
import java.util.function.Function;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.execution.ExecutionResult;
import io.yunxi.platform.chat.ChatEntity;
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
    ExecutionResult execute(ExecutionRequest request, ChatEntity conversation);

    /**
     * 执行并返回原始 AgentEvent 流（不经协议适配器 / 轨迹投影）。
     *
     * @param request      归一化执行请求（须 streaming=true）
     * @param conversation 会话实体（可为 null，非会话型入口）
     * @return 原始 AgentEvent 流
     */
    Flux<AgentEvent> executeEvents(ExecutionRequest request, ChatEntity conversation);

    /**
     * 结构化流式输出：经统一管线产出 thinking/content/tool 帧，并在 AGENT_RESULT 处集中产出 structured 帧。
     *
     * <p>等价于 {@link #executeEvents} + 门面手搓 SSE 帧的合流版本，但只保留单一 framing 管线
     * （引擎内部复用 SseProjection），消除 web 内部第二份 SSE framing。结构化对象的抽取（schema 解析 /
     * 文本自解析）由调用方以 {@code structuredExtractor} 注入，引擎只负责把抽取结果封装为 structured 帧。</p>
     *
     * <p>调用方负责在返回流前后拼接边缘层 start(requestId) / done 帧（HTTP 边缘只做传输 framing）。</p>
     *
     * @param request             归一化执行请求（须 streaming=true 且 structuredOutput=true）
     * @param conversation        会话实体（可为 null，非会话型入口）
     * @param structuredExtractor 从 AGENT_RESULT 的 Msg 抽取结构化对象（可为 null，为 null 时退化为普通流式）
     * @param cancellationCheck  取消令牌判定（可为 null）
     * @return 已封装 SSE 帧的流（含 thinking/content/tool/structured）
     */
    Flux<String> frameStructuredStream(ExecutionRequest request, ChatEntity conversation,
                                       Function<Msg, Object> structuredExtractor,
                                       BooleanSupplier cancellationCheck);

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
