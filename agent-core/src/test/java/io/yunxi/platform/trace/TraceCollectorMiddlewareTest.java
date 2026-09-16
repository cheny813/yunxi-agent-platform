package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.middleware.AgentInput;
import reactor.core.publisher.Flux;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TraceCollectorMiddleware} 单元测试：M1 的接线点。
 *
 * <p>本类守护的核心约束是<b>归集状态必须跨事件存活</b>。最初的实现按事件逐个调用
 * {@code composer.compose(Flux.just(event))}，而该方法内部每次新建归集状态，
 * 于是节点栈恒为空、配对键无从匹配、父子关系全部丢失 —— 输出仍是一串合法快照，
 * 却失去了树的形状。这类错误不会抛异常，只会静默产出错误结构，因此必须有断言守住。</p>
 */
@DisplayName("TraceCollectorMiddleware 轨迹归集接线")
class TraceCollectorMiddlewareTest {

    private InMemoryTraceStore store;
    private TraceCollectorMiddleware middleware;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore();
        middleware = new TraceCollectorMiddleware(new TraceComposer(), store);
    }

    private static Agent agentNamed(String name) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(name);
        return agent;
    }

    /** 跑一次 onAgent，把给定事件流交给中间件。 */
    private void run(Flux<AgentEvent> events, String userId, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
        Function<AgentInput, Flux<AgentEvent>> next = input -> events;
        middleware.onAgent(agentNamed("agent-a"), ctx, new AgentInput(List.of()), next)
                .collectList()
                .block();
    }

    /** 一段完整回合：开始 → 模型调用 → 工具调用 → 结束。 */
    private static Flux<AgentEvent> fullTurn() {
        return Flux.just(
                new AgentStartEvent("sess", "r1", "agent-a"),
                new ModelCallStartEvent("r1"),
                new ModelCallEndEvent("r1", null),
                new ToolCallStartEvent("r1", "call-1", "read_file"),
                new ToolCallEndEvent("r1", "call-1", "read_file"),
                new AgentEndEvent("r1"));
    }

    @Test
    @DisplayName("归集结果落库：回合产生 TURN / MODEL_CALL / TOOL_CALL 三类节点")
    void collectsSpansIntoTraceStore() {
        run(fullTurn(), "u1", "s1");

        List<ReasoningSpan> spans = store.read("u1:s1");
        assertThat(spans).isNotEmpty();
        assertThat(spans).extracting(ReasoningSpan::kind)
                .as("三类节点均应出现")
                .contains(SpanKind.TURN, SpanKind.MODEL_CALL, SpanKind.TOOL_CALL);
    }

    @Test
    @DisplayName("关键约束：归集状态跨事件存活，开启快照带父节点归属")
    void keepsStateAcrossEventsSoParentChildHolds() {
        run(fullTurn(), "u1", "s1");

        List<ReasoningSpan> children = store.read("u1:s1").stream()
                .filter(s -> s.kind() == SpanKind.MODEL_CALL || s.kind() == SpanKind.TOOL_CALL)
                .filter(ReasoningSpan::isOpen)
                .toList();

        assertThat(children).as("应归集出模型调用与工具调用节点").isNotEmpty();
        // 只对开启快照断言父节点：父子归属在节点开启时确定并入栈，关闭时节点已出栈，
        // 故关闭快照的 parentKey 本应为 null —— 这是归集器的既定语义，不是缺陷。
        assertThat(children)
                .as("每个子节点在开启时必须有父节点归属 —— 逐事件新建归集状态会让它全为 null")
                .allSatisfy(s -> assertThat(s.parentKey())
                        .as("%s 节点应挂在回合节点之下", s.kind())
                        .isEqualTo(SpanKind.TURN.name() + "|-|r|r1"));
    }

    @Test
    @DisplayName("父子归属不在关闭快照上重复表达（避免误解为丢失）")
    void closedSnapshotCarriesNoParentKeyByDesign() {
        run(fullTurn(), "u1", "s1");

        List<ReasoningSpan> closed = store.read("u1:s1").stream()
                .filter(ReasoningSpan::isClosed)
                .filter(s -> s.kind() != SpanKind.TURN)
                .toList();

        assertThat(closed).isNotEmpty();
        assertThat(closed)
                .as("关闭快照的 parentKey 恒为 null，父节点应在开启快照上读取")
                .allSatisfy(s -> assertThat(s.parentKey()).isNull());
    }

    @Test
    @DisplayName("关键约束：同一节点的开启与关闭归到同一 stableKey（非逐事件新建状态）")
    void openAndCloseShareSameStableKey() {
        run(fullTurn(), "u1", "s1");

        List<ReasoningSpan> toolSpans = store.read("u1:s1").stream()
                .filter(s -> s.kind() == SpanKind.TOOL_CALL)
                .toList();

        assertThat(toolSpans.size())
                .as("工具调用应有开启与关闭两条快照")
                .isGreaterThanOrEqualTo(2);
        assertThat(toolSpans.stream().map(ReasoningSpan::stableKey).distinct().count())
                .as("两条快照必须共享同一个 stableKey，否则配对失效")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("轨迹标识按用户与会话组合，不同用户不混桶")
    void traceIdIsUserScoped() {
        run(fullTurn(), "u1", "same-session");
        run(fullTurn(), "u2", "same-session");

        assertThat(store.read("u1:same-session")).isNotEmpty();
        assertThat(store.read("u2:same-session")).isNotEmpty();
        assertThat(store.read("same-session"))
                .as("不应退化成仅按 sessionId 分桶")
                .isEmpty();
    }

    @Test
    @DisplayName("无会话身份：跳过归集且不影响事件流透传")
    void skipsWhenNoSessionIdentity() {
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").build();
        List<AgentEvent> passthrough = middleware
                .onAgent(agentNamed("agent-a"), ctx, new AgentInput(List.of()),
                        input -> Flux.just(new AgentStartEvent("x", "r1", "a")))
                .collectList()
                .block();

        assertThat(passthrough)
                .as("事件必须原样透传，执行不受归集跳过影响")
                .hasSize(1);
    }

    @Test
    @DisplayName("归集全程不改写事件流（旁路语义）")
    void doesNotMutateEventStream() {
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        List<AgentEvent> out = middleware
                .onAgent(agentNamed("agent-a"), ctx, new AgentInput(List.of()),
                        input -> fullTurn())
                .collectList()
                .block();

        assertThat(out)
                .as("归集是旁路观察，事件数量与顺序均不得改变")
                .hasSize(6);
        assertThat(out.get(0)).isInstanceOf(AgentStartEvent.class);
        assertThat(out.get(5)).isInstanceOf(AgentEndEvent.class);
    }

    @Test
    @DisplayName("order 最高：归集器必须看到全部下游节点")
    void runsAtOutermostLayer() {
        assertThat(middleware.order())
                .as("须大于 AgentPhaseMiddleware(100) 与 AgentMetricsMiddleware(200)")
                .isGreaterThan(200);
    }

    @Test
    @DisplayName("存储故障不中断执行（归集异常被吞掉）")
    void storageFailureDoesNotBreakExecution() {
        TraceStore failing = new TraceStore() {
            @Override
            public void append(ReasoningSpan span) {
                throw new IllegalStateException("存储不可用");
            }

            @Override
            public List<ReasoningSpan> read(String traceId) {
                return List.of();
            }

            @Override
            public Flux<ReasoningSpan> stream(String traceId) {
                return Flux.empty();
            }

            @Override
            public void clear(String traceId) {
            }
        };
        TraceCollectorMiddleware resilient =
                new TraceCollectorMiddleware(new TraceComposer(), failing);

        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        List<AgentEvent> out = resilient
                .onAgent(agentNamed("agent-a"), ctx, new AgentInput(List.of()),
                        input -> fullTurn())
                .collectList()
                .block();

        assertThat(out)
                .as("轨迹是观测资产，存储故障不得中断执行")
                .hasSize(6);
    }
}
