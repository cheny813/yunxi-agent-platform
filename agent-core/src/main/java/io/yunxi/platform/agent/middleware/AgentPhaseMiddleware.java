package io.yunxi.platform.agent.middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.platform.execution.AgentPhase;
import reactor.core.publisher.Flux;

/**
 * 执行阶段归集中间件（AgentScope-Java 原生扩展件，onAgent 钩子）。
 *
 * <p>把 Agent 事件流推导为用户可见的执行阶段（IDLE → THINKING → TOOL_CALL → ANSWER → DONE），
 * 并在阶段切换处注入 {@code CustomEvent(name="agent_status")}。阶段推导是纯函数式的：同一事件
 * 类型恒定映射到同一阶段，不依赖历史状态。</p>
 *
 * <p>阶段轨迹按会话槽位保留最近一次执行的快照，供 {@code /agent/{name}/status} 读取。该内存态
 * 仅用于观测，不参与执行决策。</p>
 *
 * <p><b>Why 中间件而非引擎内联算子</b>：阶段归集是「拦整个 Agent 调用、包装前后」的关切，
 * 正是 {@link MiddlewareBase#onAgent} 的语义。放在引擎里意味着 Agent 的每一次调用方式变更
 * （换入口、加子代理、走 channel）都要同步改引擎；放在中间件里则随 Agent 构建一次性装配，
 * 任何调用入口自动生效，包括 Supervisor 转发上来的子代理事件。</p>
 *
 * @author yunxi-agent-platform
 */
public class AgentPhaseMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentPhaseMiddleware.class);

    /** 阶段标记事件名（SSE 适配器据此转换为 agent_status 协议负载） */
    public static final String AGENT_STATUS_EVENT_NAME = "agent_status";

    /** 阶段轨迹保留的最大会话数，超出后按插入顺序淘汰最早的一条 */
    private static final int MAX_TRACKED_SESSIONS = 4096;

    /** 会话槽位 → 最近一次执行的阶段轨迹（观测用内存态） */
    private final Map<String, PhaseTrace> lastTraceBySlot = new ConcurrentHashMap<>();

    /**
     * 最近一次执行的阶段快照。
     *
     * @param trace     阶段轨迹（阶段名列表，含初始 IDLE）
     * @param timestamp 执行完成时间戳（毫秒）
     */
    public record PhaseTrace(List<String> trace, long timestamp) {
    }

    @Override
    public int order() {
        // 高于默认 1：尽量贴近洋葱外层，使阶段推导覆盖其余中间件产生的耗时
        return 100;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent,
                                    RuntimeContext ctx,
                                    AgentInput input,
                                    java.util.function.Function<AgentInput, Flux<AgentEvent>> next) {
        String slotKey = slotKeyOf(agent, ctx);
        AtomicReference<AgentPhase> current = new AtomicReference<>(AgentPhase.IDLE);
        List<AgentPhase> trace = new ArrayList<>();
        trace.add(AgentPhase.IDLE);

        // 逐个事件推导阶段：切换时先发出阶段标记，再发出原事件
        Flux<AgentEvent> mapped = next.apply(input).concatMap(event -> {
            AgentPhase phase = derivePhase(event.getType());
            if (phase == null || phase == current.get()) {
                return Flux.just(event);
            }
            current.set(phase);
            trace.add(phase);
            return Flux.just(phaseEvent(phase), event);
        });

        // 正常结束补充 DONE 标记；出错补充 ERROR 标记并原样传出异常
        return mapped
                .concatWith(Flux.defer(() -> doneEventOrEmpty(current, trace)))
                .onErrorResume(e -> Flux.defer(() -> {
                    if (current.get() != AgentPhase.ERROR) {
                        current.set(AgentPhase.ERROR);
                        trace.add(AgentPhase.ERROR);
                    }
                    return Flux.just(phaseEvent(AgentPhase.ERROR));
                }).concatWith(Flux.error(e)))
                .doOnComplete(() -> recordTrace(slotKey, trace))
                .doOnError(e -> recordTrace(slotKey, trace));
    }

    /**
     * 查询某会话最近一次执行的阶段轨迹（无记录返回 null）。
     *
     * @param agentName Agent 名（无会话身份时的回退键）
     * @param sessionId 会话 ID（优先）
     * @return 阶段轨迹快照，无记录时为 null
     */
    public PhaseTrace getLastPhaseTrace(String agentName, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            PhaseTrace bySession = lastTraceBySlot.get(sessionId);
            if (bySession != null) {
                return bySession;
            }
        }
        return agentName == null ? null : lastTraceBySlot.get(slotKey(agentName, null));
    }

    /**
     * 旧签名兼容：仅按 Agent 名查询（无会话身份的调用方使用）。
     *
     * @param agentName Agent 名
     * @return 阶段轨迹快照，无记录时为 null
     */
    public PhaseTrace getLastPhaseTrace(String agentName) {
        return getLastPhaseTrace(agentName, null);
    }

    /**
     * 流正常结束时补充 DONE 标记；已处于 DONE 或 ERROR 时不再重复补充。
     */
    private static Flux<AgentEvent> doneEventOrEmpty(AtomicReference<AgentPhase> current,
                                                     List<AgentPhase> trace) {
        AgentPhase last = current.get();
        if (last == AgentPhase.DONE || last == AgentPhase.ERROR) {
            return Flux.empty();
        }
        current.set(AgentPhase.DONE);
        trace.add(AgentPhase.DONE);
        return Flux.just(phaseEvent(AgentPhase.DONE));
    }

    private void recordTrace(String slotKey, List<AgentPhase> trace) {
        if (slotKey == null) {
            return;
        }
        if (lastTraceBySlot.size() >= MAX_TRACKED_SESSIONS && !lastTraceBySlot.containsKey(slotKey)) {
            // 观测态无需精确淘汰：容量到顶时清空重来，避免无界增长
            log.debug("阶段轨迹表已达上限 {}，清空重建", MAX_TRACKED_SESSIONS);
            lastTraceBySlot.clear();
        }
        List<String> phaseNames = trace.stream().map(AgentPhase::name).toList();
        lastTraceBySlot.put(slotKey, new PhaseTrace(phaseNames, System.currentTimeMillis()));
    }

    /**
     * 会话槽位键：优先 (sessionId)，回退 agentName，再回退 agentId。
     *
     * <p>状态查询接口按 Agent 名查，而阶段归集按会话隔离，故两个键都要能命中，
     * 见 {@link #getLastPhaseTrace(String, String)} 的双键查找。</p>
     */
    private static String slotKeyOf(Agent agent, RuntimeContext ctx) {
        if (ctx != null && ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
            return ctx.getSessionId();
        }
        String agentName = agent == null ? null : agent.getName();
        return slotKey(agentName, null);
    }

    private static String slotKey(String agentName, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        return (agentName == null || agentName.isBlank()) ? null : agentName;
    }

    /**
     * 事件类型 → 阶段映射。返回 null 表示未映射（保持当前阶段）。
     */
    private static AgentPhase derivePhase(AgentEventType type) {
        if (type == null) {
            return null;
        }
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
     * 构造阶段标记事件，由 SSE 适配器识别并转换为 {@code agent_status} 协议负载。
     */
    private static AgentEvent phaseEvent(AgentPhase phase) {
        return new CustomEvent(AGENT_STATUS_EVENT_NAME,
                Map.of("phase", phase.name(), "label", phase.getLabel()));
    }
}
