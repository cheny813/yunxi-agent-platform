package io.yunxi.platform.execution.operator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.CustomEvent;
import io.yunxi.platform.execution.AgentPhase;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.EventOperator;
import reactor.core.publisher.Flux;

/**
 * 阶段追踪算子。
 *
 * <p>对 {@code Flux<AgentEvent>} 做纯函数阶段推导（事件类型 → 阶段），仅在阶段
 * 切换时以 {@link CustomEvent}(name="agent_status") 形式注入一条阶段标记，
 * 由 {@code SseAgentEventAdapter} 转换为 {@code agent_status} 协议负载
 * （IDLE→THINKING→TOOL_CALL→ANSWER→DONE），流的结束信号由本算子
 * 注入的 DONE 阶段标记承担，取代流编排层硬编码的"处理完成"收尾。</p>
 *
 * <p>阶段推导表：</p>
 * <ul>
 *   <li>IDLE：初始 / 前置链完成；</li>
 *   <li>THINKING：MODEL_CALL_START / THINKING_BLOCK_START(+DELTA)；</li>
 *   <li>TOOL_CALL：TOOL_CALL_START；</li>
 *   <li>THINKING：TOOL_CALL_END / TOOL_RESULT_END（回到推理）；</li>
 *   <li>ANSWER：TEXT_BLOCK_START / TEXT_BLOCK_DELTA / AGENT_RESULT；</li>
 *   <li>ERROR：异常 / ERROR 事件；</li>
 *   <li>DONE：流正常完成。</li>
 * </ul>
 *
 * <p>阶段轨迹（{@code List<AgentPhase>}）写入 ExecutionContext.attributes
 * （{@value #ATTR_PHASE_TRACE} / {@value #ATTR_LAST_PHASE}），并保留最近一次
 * 执行的阶段轨迹（{@link #getLastPhaseTrace(String)}）供
 * {@code /api/conversations/agent/{name}/status} 查询。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class PhaseTracker implements EventOperator {

    /** 阶段标记 CustomEvent 的 name */
    public static final String AGENT_STATUS_EVENT_NAME = "agent_status";
    /** 阶段轨迹在 ExecutionContext.attributes 中的键（List<String>） */
    public static final String ATTR_PHASE_TRACE = "phaseTrace";
    /** 最近阶段在 ExecutionContext.attributes 中的键（String） */
    public static final String ATTR_LAST_PHASE = "lastPhase";

    /** agentName → 最近一次执行的阶段轨迹（供 status 端点查询，内存态） */
    private final Map<String, PhaseTrace> lastTraceByAgent = new ConcurrentHashMap<>();

    /**
     * 最近一次执行的阶段快照。
     *
     * @param trace     阶段轨迹（阶段名列表，含初始 IDLE）
     * @param timestamp 执行完成时间戳（毫秒）
     */
    public record PhaseTrace(List<String> trace, long timestamp) {
    }

    @Override
    public int getOrder() {
        return 700;
    }

    @Override
    public Flux<AgentEvent> apply(Flux<AgentEvent> events, ExecutionContext ctx) {
        AtomicReference<AgentPhase> current = new AtomicReference<>(AgentPhase.IDLE);
        // 轨迹（含初始 IDLE），随执行进度追加
        List<AgentPhase> trace = new ArrayList<>();
        trace.add(AgentPhase.IDLE);

        Flux<AgentEvent> mapped = events
                .concatMap(event -> {
                    AgentPhase phase = derivePhase(event.getType());
                    if (phase == null || phase == current.get()) {
                        // 未映射或未切换：透传原事件
                        return Flux.just(event);
                    }
                    // 阶段切换：先发阶段标记，再透传原事件
                    current.set(phase);
                    trace.add(phase);
                    return Flux.just(phaseEvent(phase), event);
                });

        // 正常完成时注入 DONE 阶段标记（充当流的结束信号）
        Flux<AgentEvent> withDone = Flux.concat(mapped, Flux.defer(() -> {
            AgentPhase last = current.get();
            if (last != AgentPhase.DONE && last != AgentPhase.ERROR) {
                current.set(AgentPhase.DONE);
                trace.add(AgentPhase.DONE);
                return Flux.just(phaseEvent(AgentPhase.DONE));
            }
            return Flux.empty();
        }));

        return withDone
                .onErrorResume(e -> {
                    // 注入 ERROR 阶段标记后重抛，错误消息由上层流编排的 onErrorResume 兜底
                    current.set(AgentPhase.ERROR);
                    trace.add(AgentPhase.ERROR);
                    return Flux.concat(Flux.just(phaseEvent(AgentPhase.ERROR)), Flux.error(e));
                })
                .doOnComplete(() -> recordTrace(ctx, current.get(), trace))
                .doOnError(e -> recordTrace(ctx, current.get(), trace));
    }

    private void recordTrace(ExecutionContext ctx, AgentPhase last, List<AgentPhase> trace) {
        List<String> phaseNames = trace.stream()
                .map(phase -> phase.name())
                .toList();
        ctx.setAttribute(ATTR_PHASE_TRACE, phaseNames);
        ctx.setAttribute(ATTR_LAST_PHASE, last.name());
        String agentName = ctx.getAgentName();
        if (agentName != null && !agentName.isBlank()) {
            lastTraceByAgent.put(agentName, new PhaseTrace(phaseNames, System.currentTimeMillis()));
        }
    }

    /**
     * 查询最近一次执行的阶段轨迹（无记录返回 null）。
     */
    public PhaseTrace getLastPhaseTrace(String agentName) {
        return agentName == null ? null : lastTraceByAgent.get(agentName);
    }

    /**
     * 事件类型 → 阶段映射。返回 null 表示未映射（保持当前阶段）。
     */
    private static AgentPhase derivePhase(AgentEventType type) {
        switch (type) {
            case MODEL_CALL_START:
            case THINKING_BLOCK_START:
            case THINKING_BLOCK_DELTA:
                return AgentPhase.THINKING;
            case TOOL_CALL_START:
                return AgentPhase.TOOL_CALL;
            case TOOL_CALL_END:
            case TOOL_RESULT_END:
                return AgentPhase.THINKING;
            case TEXT_BLOCK_START:
            case TEXT_BLOCK_DELTA:
            case AGENT_RESULT:
                return AgentPhase.ANSWER;
            default:
                return null;
        }
    }

    /**
     * 构造阶段标记事件：以 CustomEvent(name="agent_status") 形式注入事件流，
     * 由 SseAgentEventAdapter 识别并转换为 agent_status 协议负载。
     */
    private static CustomEvent phaseEvent(AgentPhase phase) {
        return new CustomEvent(AGENT_STATUS_EVENT_NAME,
                Map.of("phase", phase.name(), "label", phase.getLabel()));
    }
}
