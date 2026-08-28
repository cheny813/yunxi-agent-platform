package io.yunxi.platform.execution.spi;

import reactor.core.publisher.Flux;

import io.agentscope.core.event.AgentEvent;
import io.yunxi.platform.execution.ExecutionContext;

/**
 * 事件算子：对 {@code Flux<AgentEvent>} 进行横切处理（指标、阶段追踪、日志等）。
 *
 * <p>作用范围：算子链<b>仅作用于流式通道</b>（含流式 QUICK）——
 * 流式通道事件流经算子链后再进入协议适配器；阻塞通道由策略内部直接收尾
 * （BlockingStrategy 在 streamEvents 末端聚合 Msg），<b>不经过算子链</b>。
 * 算子可观察/记录事件流，但不应改变事件的语义内容（除非明确标注转换算子）。</p>
 *
 * @author yunxi-agent-platform
 */
public interface EventOperator {

    /**
     * 算子执行顺序，数值越小越先应用。
     * 约定：Metrics=600, PhaseTracker=700。
     *
     * @return 顺序值（默认 0）
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 对事件流应用本算子。
     *
     * @param events 原始事件流
     * @param ctx    执行上下文（用于关联 agentName / userId / conversationId）
     * @return 处理后的事件流（默认透传）
     */
    Flux<AgentEvent> apply(Flux<AgentEvent> events, ExecutionContext ctx);
}
