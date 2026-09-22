package io.yunxi.platform.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.state.AgentState;

/**
 * 轨迹归集器：把事件流归集为轨迹节点快照并落库，供所有执行通道共用。
 *
 * <p><b>为什么必须共用一份</b>：轨迹归集是「把事件流转换为观测资产」这一条横切关切。
 * 若每个执行通道各自接一条归集链路，就会出现通道之间的行为差异 —— 走某条通道有轨迹、
 * 走另一条没有，而这类差异在故障时极难定位（现象是「查不到数据」，原因在别处的编排）。
 * 因此归集的入口只允许有一个，由执行引擎统一调用。</p>
 *
 * <p><b>一次调用 = 一棵轨迹树 = 一个归集会话</b>。会话持有跨事件的归集状态（节点栈、
 * 配对键、未决计数），必须复用而非逐事件重建。</p>
 *
 * <p><b>旁路语义</b>：归集结果不回注事件流，全过程吞掉异常。轨迹是观测资产，
 * 其故障不应中断执行，但必须被计数 —— 见 {@link TraceStore} 的写入失败告警。</p>
 *
 * <p><b>与归集中间件的关系</b>：另有一个等价的中间件实现，但 Agent 侧装配链路未启用它。
 * 两者是同一关切的两份实现，属待收敛项。本类是被实际调用的那一份。</p>
 *
 * @author yunxi-agent-platform
 */
public class TraceCollector {

    private static final Logger log = LoggerFactory.getLogger(TraceCollector.class);

    /** 任务清单写入工具名 —— 该工具的调用参数不在事件流里，清单需从 AgentState 旁路读取 */
    private static final String TODO_WRITE_TOOL = "todo_write";

    /** 已接收的事件数：用于区分「归集器未被调用」与「调用了但没产生快照」 */
    private final AtomicLong eventCount = new AtomicLong();

    /** 已落库的快照数：非零即证明写入链路是通的 */
    private final AtomicLong persistedCount = new AtomicLong();

    private final TraceComposer composer;
    private final TraceStore traceStore;

    /** 每落库多少条快照打一次进度日志 */
    private static final long LOG_EVERY_N_SPANS = 10L;

    public TraceCollector(TraceComposer composer, TraceStore traceStore) {
        this.composer = composer;
        this.traceStore = traceStore;
    }

    /**
     * 开启一次调用对应的归集会话。
     *
     * @param traceId 轨迹标识，为 null 时返回 null 表示本次调用不归集
     * @return 归集会话；traceId 为空时返回 null
     */
    public TraceComposer.Session openSession(String traceId) {
        return traceId == null ? null : composer.session(traceId);
    }

    /**
     * 归集单个事件并落库。
     *
     * <p>逐事件归集（而非整条流一次性归集）是为了让轨迹能随执行推进实时落库 ——
     * 长回合执行到一半时，已发生的节点已经可被订阅方看到，不必等流结束。</p>
     *
     * <p>任务清单在工具调用到达时旁路注入：{@code todo_write} 的参数不随事件下发，
     * 清单落在 {@code AgentState.tasksContext}，需从调用作用域状态读取。</p>
     *
     * @param session 归集会话，为 null 时不做任何事
     * @param event   框架事件
     * @param ctx     运行时上下文，用于旁路读取任务清单，可为 null
     * @return 本次归集产生的节点快照（已落库），供调用方做协议投影；会话为 null 时返回空列表
     */
    public List<ReasoningSpan> accept(TraceComposer.Session session, AgentEvent event,
                                      RuntimeContext ctx) {
        if (session == null) {
            return List.of();
        }
        eventCount.incrementAndGet();
        List<ReasoningSpan> spans;
        try {
            spans = session.accept(event);
        } catch (Exception e) {
            log.warn("轨迹归集失败: {}", e.getMessage());
            return List.of();
        }
        if (event instanceof ToolCallStartEvent start
                && TODO_WRITE_TOOL.equals(start.getToolCallName())) {
            spans = withTodoSnapshot(session, ctx, start, spans);
        }
        persist(session, spans);
        return spans;
    }

    /**
     * 收尾：为仍未关闭的节点补关闭快照，避免轨迹里出现无终点的节点。
     *
     * @param session 归集会话，为 null 时不做任何事
     */
    public void close(TraceComposer.Session session) {
        if (session == null) {
            return;
        }
        try {
            persist(session, session.close());
        } catch (Exception e) {
            log.warn("轨迹收尾失败: {}", e.getMessage());
        }
    }

    /**
     * 注入编排层产生、事件流里不存在的节点。
     *
     * <p>意图分析在请求解析阶段完成（早于 Agent 调用），运行时不知道它的存在，
     * 事件流里自然没有对应事件 —— 但它是「为什么走到这个子流程」的唯一答案来源。</p>
     *
     * @param session 归集会话
     * @param kind    节点类型
     * @param payload 节点负载
     * @param durationMs 节点耗时（毫秒）
     */
    public void inject(TraceComposer.Session session, SpanKind kind, Map<String, Object> payload,
                       long durationMs) {
        if (session == null) {
            return;
        }
        try {
            persist(session, session.inject(kind, payload, durationMs));
        } catch (Exception e) {
            log.debug("节点注入失败: {}", e.getMessage());
        }
    }

    /**
     * 旁路读取任务清单并注入为 PLAN 节点。
     *
     * <p>读取经 {@link RuntimeContext#resolveAgentState} —— 它是<b>调用作用域</b>的状态，
     * 并发会话下取到的必然是本次调用的那一个；不使用 {@code agent.getAgentState()}，
     * 那在多租户并发下会串到别的会话。</p>
     */
    private List<ReasoningSpan> withTodoSnapshot(TraceComposer.Session session, RuntimeContext ctx,
                                                 ToolCallStartEvent start,
                                                 List<ReasoningSpan> spans) {
        if (ctx == null) {
            return spans;
        }
        try {
            AgentState state = RuntimeContext.resolveAgentState(ctx, null);
            if (state == null || state.getTasksContext() == null) {
                return spans;
            }
            List<Map<String, Object>> tasks = state.getTasksContext().getTasks().stream()
                    .map(t -> {
                        Map<String, Object> item = new java.util.LinkedHashMap<>();
                        item.put("id", t.getId());
                        item.put("subject", t.getSubject());
                        item.put("state", t.getState() == null ? null : t.getState().name());
                        return item;
                    })
                    .toList();
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("tasks", tasks);
            payload.put("taskCount", tasks.size());
            payload.put("toolCallId", start.getToolCallId());
            List<ReasoningSpan> merged = new ArrayList<>(spans);
            merged.addAll(session.inject(SpanKind.PLAN, payload, 0L));
            return merged;
        } catch (Exception e) {
            log.debug("任务清单归集失败: {}", e.getMessage());
            return spans;
        }
    }

    private void persist(TraceComposer.Session session, List<ReasoningSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }
        int ok = 0;
        for (ReasoningSpan span : spans) {
            try {
                traceStore.append(span);
                ok++;
            } catch (Exception e) {
                log.warn("轨迹落库失败: traceId={}: {}", span.traceId(), e.getMessage());
            }
        }
        long before = persistedCount.getAndAdd(ok);
        // 归集链路可见性：把「归集器接收了多少事件、实际落库多少快照」记下来。
        // 没有这条信号时，「会话未开启」「归集器未被调用」「落库失败」三种故障在日志上完全同形，
        // 现象都只是「查不到轨迹」，而原因分别在本处、调用方与存储侧。
        if (log.isInfoEnabled() && before / LOG_EVERY_N_SPANS < persistedCount.get() / LOG_EVERY_N_SPANS) {
            log.info("[trace] 归集进行中: 已接收事件={}, 已落库快照={}",
                    eventCount.get(), persistedCount.get());
        }
    }

    /**
     * 本次进程内累计接收的事件数，供诊断端点读取。
     *
     * @return 事件数
     */
    public long receivedEventCount() {
        return eventCount.get();
    }

    /**
     * 本次进程内累计落库的快照数，供诊断端点读取。
     *
     * @return 快照数
     */
    public long persistedSpanCount() {
        return persistedCount.get();
    }

}
