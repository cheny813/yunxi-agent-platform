package io.yunxi.platform.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import io.agentscope.core.event.AgentEvent;
import io.yunxi.platform.execution.spi.EventOperator;
import reactor.core.publisher.Flux;

/**
 * 事件算子链。
 *
 * <p>收集 Spring 容器内全部 {@link EventOperator}（@Component），按
 * {@code getOrder()} 升序折叠应用到 Agent 事件流。约定：Metrics=600、
 * PhaseTracker=700。算子链<b>仅作用于流式通道</b>（引擎仅在 streaming 分支调用本链，
 * 见 AgentExecutionEngine#execute）；阻塞通道由 BlockingStrategy 内部直接聚合 Msg，
 * 不经过算子链（阻塞通道以 Msg 聚合收尾，流程中不产生可被算子消费的事件流）。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class EventOperatorChain {

    private final List<EventOperator> operators;

    public EventOperatorChain(List<EventOperator> operators) {
        List<EventOperator> sorted = new ArrayList<>(operators);
        sorted.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        this.operators = Collections.unmodifiableList(sorted);
    }

    /**
     * 按序应用全部算子。
     *
     * @param events 原始事件流
     * @param ctx    执行上下文
     * @return 处理后的事件流
     */
    public Flux<AgentEvent> apply(Flux<AgentEvent> events, ExecutionContext ctx) {
        Flux<AgentEvent> current = events;
        for (EventOperator operator : operators) {
            current = operator.apply(current, ctx);
        }
        return current;
    }
}
