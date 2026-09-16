package io.yunxi.platform.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

/**
 * 轨迹归集中间件（AgentScope-Java 原生扩展件，onAgent 钩子）。
 *
 * <p>把事件流经 {@link TraceComposer} 归集为轨迹节点快照，再写入 {@link TraceStore}。
 * 这是 M1 的接线点 —— 归集器与存储在实现层面早已就位，缺的只是「谁在运行时调用它们」。</p>
 *
 * <p><b>旁路语义</b>：归集结果不回注事件流，原事件原样向下游传递。归集发生在独立订阅上，
 * 因此归集失败（字段异常、存储故障）不会中断执行 —— 轨迹是观测资产，不该成为可用性风险。</p>
 *
 * <p><b>为何在 onAgent 而非更内层钩子</b>：一棵轨迹对应一个用户回合，而一个回合等于
 * 一次 {@code agent.call}。{@code onAgent} 恰好是这次调用的边界，且它是唯一能同时看到
 * 内层全部事件（模型调用、工具调用、块增量、HITL、子代理转发）的位置。放在更内层会漏掉
 * 平级节点，放在引擎里则会重复 M1 之前那种「每个通道各接一次」的错误。</p>
 *
 * <p><b>与 {@code AgentPhaseMiddleware} 的区别</b>：阶段归集要<b>改写</b>事件流（注入
 * {@code agent_status} 标记），故必须占据洋葱链的返回路径；轨迹归集只<b>旁路观察</b>，
 * 不参与事件流，因此不改变任何下游行为。两者都读同一份事件，但一个在链上、一个在链外。</p>
 *
 * @author yunxi-agent-platform
 */
public class TraceCollectorMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(TraceCollectorMiddleware.class);

    /** 轨迹标识在运行时上下文中的键 */
    private static final String TRACE_ID_KEY = "yunxi.trace.traceId";

    /** 待注入的意图节点数据在运行时上下文中的键（由编排层写入） */
    public static final String INTENT_SPAN_KEY = "yunxi.trace.intentSpan";

    /** 任务清单写入工具名 —— 该工具的调用参数不在事件流里，清单需从 AgentState 旁路读取 */
    private static final String TODO_WRITE_TOOL = "todo_write";

    /**
     * 编排层意图节点的载荷载体。
     *
     * <p>刻意做成极简的数据袋而非直接传 {@code IntentResult}：本中间件属于产品平面的观测件，
     * 不应依赖意图引擎的类型。载荷由编排层自行决定装什么，中间件只负责把它挂到树上。</p>
     *
     * @param payload    节点负载
     * @param durationMs 节点耗时（毫秒）
     */
    public record IntentSpanData(java.util.Map<String, Object> payload, long durationMs) {
    }

    private final TraceComposer composer;
    private final TraceStore traceStore;

    public TraceCollectorMiddleware(TraceComposer composer, TraceStore traceStore) {
        this.composer = composer;
        this.traceStore = traceStore;
    }

    @Override
    public int order() {
        // 贴近洋葱最外层（数值最大）：必须看到全部下游节点，
        // 包括其它中间件在更内层产生的 CustomEvent。
        return 1000;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent,
                                    RuntimeContext ctx,
                                    AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String traceId = resolveTraceId(ctx, agent);
        if (traceId == null) {
            // 无会话身份则无法为轨迹分桶，跳过归集（执行不受影响）
            log.debug("无会话身份，跳过轨迹归集");
            return next.apply(input);
        }

        // 一次调用 = 一棵轨迹树 = 一个归集会话。会话持有跨事件的归集状态（节点栈、
        // 配对键、未决计数），因此必须在本方法内创建并复用，不能逐事件新建。
        TraceComposer.Session session = composer.session(traceId);
        // 编排层节点只注入一次；失败或无数据时不重试
        boolean[] orchestrationInjected = {false};

        return next.apply(input)
                .doOnNext(event -> {
                    collect(traceId, session, event);
                    injectTodoSnapshotIfNeeded(session, ctx, event);
                })
                .doOnComplete(() -> finish(traceId, session))
                .doOnError(err -> finish(traceId, session));
    }

    /**
     * 注入编排层产生、事件流里不存在的节点。
     *
     * <p>当前只有意图一个来源。意图分析在请求解析阶段完成（早于 Agent 调用），
     * AgentScope-Java 运行时不知道它的存在，事件流里自然没有对应事件 ——
     * 但它是「为什么走到这个子流程」的唯一答案来源，不入树则该问题永远答不出来。</p>
     *
     * <p><b>调用时机是硬约束</b>：必须在回合节点开启之后调用。归集器的父子归属取决于
     * 调用那一刻的节点栈 —— 早于回合节点开启调用只能得到游离节点（栈为空，父指针为 null），
     * 晚于工具节点压栈调用则会误挂到工具之下。见 {@code onAgent} 中注入点的说明。</p>
     *
     * <p>数据经 {@link #INTENT_SPAN_KEY} 从编排层传入。用运行时上下文承载而非直接依赖
     * 编排层的类型，是为了让本中间件只认「有一个意图节点要注入」这一件事，
     * 不反向依赖执行引擎的内部结构。</p>
     */
    /**
     * 任务清单归集：工具名为 {@code todo_write} 的调用到达时，把清单快照注入为一个 PLAN 节点。
     *
     * <p><b>为什么清单不在事件流里</b>：{@code todo_write} 的工具参数（模型提交的完整清单）
     * 不随工具调用事件下发 —— {@code ToolCallStartEvent} 只带标识与名称。清单落在
     * {@code AgentState.tasksContext}，由工具实现自行写入。故归集必须在事件之外旁路读取。</p>
     *
     * <p><b>为什么在工具调用开始时注入而非结束时</b>：清单是<b>全量替换</b>语义，
     * 每次调用提交的都是最新完整清单。在调用开始时注入即可反映本次写入的结果
     * （工具同步执行，参数已由模型给出）；若等结束，则当工具执行失败或超时，
     * 这张清单就永远不会出现在轨迹里，而它其实是「模型这次打算做什么」的证据。</p>
     *
     * <p>读取经 {@link RuntimeContext#getAgentState()} —— 它是<b>调用作用域</b>的状态，
     * 并发会话下取到的必然是本次调用的那一个。不使用 {@code agent.getAgentState()}，
     * 那在多租户并发下会串到别的会话。</p>
     */
    private void injectTodoSnapshotIfNeeded(TraceComposer.Session session, RuntimeContext ctx,
                                            AgentEvent event) {
        if (!(event instanceof ToolCallStartEvent start)) {
            return;
        }
        if (!TODO_WRITE_TOOL.equals(start.getToolCallName())) {
            return;
        }
        try {
            AgentState state = RuntimeContext.resolveAgentState(ctx, null);
            if (state == null || state.getTasksContext() == null) {
                return;
            }
            List<Map<String, Object>> tasks = state.getTasksContext().getTasks().stream()
                    .map(t -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", t.getId());
                        item.put("subject", t.getSubject());
                        item.put("state", t.getState() == null ? null : t.getState().name());
                        return item;
                    })
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tasks", tasks);
            payload.put("taskCount", tasks.size());
            payload.put("toolCallId", start.getToolCallId());
            for (ReasoningSpan span : session.inject(SpanKind.PLAN, payload, 0L)) {
                traceStore.append(span);
            }
        } catch (Exception e) {
            log.debug("任务清单归集失败: {}", e.getMessage());
        }
    }

    /**
     * 归集单个事件并写存储，全过程吞掉异常。
     *
     * <p>逐事件归集（而非整条流一次性归集）是为了让轨迹能随执行推进实时落库 ——
     * 长回合执行到一半时，已发生的节点已经可被订阅方看到，不必等流结束。</p>
     */
    private void collect(String traceId, TraceComposer.Session session, AgentEvent event) {
        try {
            for (ReasoningSpan span : session.accept(event)) {
                traceStore.append(span);
            }
        } catch (Exception e) {
            log.debug("轨迹归集异常: traceId={}: {}", traceId, e.getMessage());
        }
    }

    /**
     * 流结束时收尾：为仍未关闭的节点补关闭快照，避免轨迹里出现无终点的节点。
     */
    private void finish(String traceId, TraceComposer.Session session) {
        try {
            for (ReasoningSpan span : session.close()) {
                traceStore.append(span);
            }
        } catch (Exception e) {
            log.debug("轨迹收尾失败: traceId={}: {}", traceId, e.getMessage());
        }
    }

    /**
     * 解析轨迹标识：优先取运行时上下文中已注入的值，否则用会话槽位组合。
     *
     * <p>会话槽位由 {@code (userId, sessionId)} 组成 —— 单用 sessionId 会在不同用户
     * 恰好复用同一会话标识时把两条轨迹混进一个桶。上下文无会话身份时返回 null，
     * 由调用方跳过归集。</p>
     */
    private static String resolveTraceId(RuntimeContext ctx, Agent agent) {
        if (ctx == null) {
            return null;
        }
        Object injected = ctx.get(TRACE_ID_KEY);
        if (injected instanceof String s && !s.isBlank()) {
            return s;
        }
        String sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String userId = ctx.getUserId();
        return (userId == null || userId.isBlank()) ? sessionId : userId + ":" + sessionId;
    }

    /**
     * 轨迹标识的注入键，供编排层在发起调用前写入运行时上下文覆盖默认取值。
     *
     * @return 运行时上下文中轨迹标识的属性键
     */
    public static String traceIdKey() {
        return TRACE_ID_KEY;
    }
}
