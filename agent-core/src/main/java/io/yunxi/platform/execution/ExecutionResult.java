package io.yunxi.platform.execution;

import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;

/**
 * 执行结果统一载体。
 *
 * <p>两种通道共用：阻塞通道返回 {@link #getResult()}（Msg），
 * 流式通道返回 {@link #getStream()}（SSE 字符串流）。
 * 两者都携带执行上下文（{@link #getContext()}），供门面层做
 * 会话持久化（inputMessage / conversation）等收口操作。</p>
 *
 * @author yunxi-agent-platform
 */
public class ExecutionResult {

    /** 阻塞通道结果（流式通道为 null） */
    private final Msg result;
    /** 流式通道 SSE 结果（阻塞通道为 null） */
    private final Flux<String> stream;
    /** 是否流式通道 */
    private final boolean streaming;
    /** 错误信息（非 null 表示执行失败） */
    private final String errorMessage;
    /** 执行上下文（含 inputMessage/inputMessages/conversation 等收口所需信息） */
    private final ExecutionContext context;

    private ExecutionResult(Msg result, Flux<String> stream, boolean streaming,
                            String errorMessage, ExecutionContext context) {
        this.result = result;
        this.stream = stream;
        this.streaming = streaming;
        this.errorMessage = errorMessage;
        this.context = context;
    }

    /**
     * 阻塞结果。
     */
    public static ExecutionResult blocking(Msg result, ExecutionContext context) {
        return new ExecutionResult(result, null, false, null, context);
    }

    /**
     * 流式结果。
     */
    public static ExecutionResult streaming(Flux<String> stream, ExecutionContext context) {
        return new ExecutionResult(null, stream, true, null, context);
    }

    /**
     * 错误结果（拦截器/执行异常时返回）。
     */
    public static ExecutionResult error(String errorMessage, ExecutionContext context) {
        return new ExecutionResult(null, null, false, errorMessage, context);
    }

    public Msg getResult() {
        return result;
    }

    public Flux<String> getStream() {
        return stream;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isError() {
        return errorMessage != null;
    }

    public ExecutionContext getContext() {
        return context;
    }
}
