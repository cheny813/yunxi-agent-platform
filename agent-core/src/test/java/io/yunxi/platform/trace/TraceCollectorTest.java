package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import reactor.core.publisher.Flux;

/**
 * {@link TraceCollector} 单元测试：轨迹归集的运行时接线点。
 *
 * <p>本类守护的核心约束是<b>归集状态必须跨事件存活</b>。若按事件逐个新建归集状态，
 * 节点栈恒为空、配对键无从匹配、父子关系全部丢失 —— 输出仍是一串合法快照，
 * 却失去了树的形状。这类错误不会抛异常，只会静默产出错误结构，因此必须有断言守住。</p>
 */
@DisplayName("TraceCollector 轨迹归集")
class TraceCollectorTest {

    private InMemoryTraceStore store;
    private TraceCollector collector;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore();
        collector = new TraceCollector(new TraceComposer(TestCodecs.newCodec()), store);
    }

    /** 把给定事件流喂给归集会话，返回归集会话供调用方收尾。 */
    private TraceComposer.Session feed(Flux<AgentEvent> events, String traceId) {
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        TraceComposer.Session session = collector.openSession(traceId);
        events.toIterable().forEach(event -> collector.accept(session, event, ctx));
        return session;
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
        TraceComposer.Session session = feed(fullTurn(), "u1:s1");
        collector.close(session);

        List<ReasoningSpan> spans = store.read("u1:s1");
        assertThat(spans).isNotEmpty();
        assertThat(spans).extracting(ReasoningSpan::kind)
                .as("三类节点均应出现")
                .contains(SpanKind.TURN, SpanKind.MODEL_CALL, SpanKind.TOOL_CALL);
    }

    @Test
    @DisplayName("关键约束：归集状态跨事件存活，开启快照带父节点归属")
    void keepsStateAcrossEventsSoParentChildHolds() {
        TraceComposer.Session session = feed(fullTurn(), "u1:s1");
        collector.close(session);

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
        TraceComposer.Session session = feed(fullTurn(), "u1:s1");
        collector.close(session);

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
        TraceComposer.Session session = feed(fullTurn(), "u1:s1");
        collector.close(session);

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
    @DisplayName("收尾补齐：未关闭的节点在 close 时补关闭快照")
    void closeEmitsClosingSnapshots() {
        // 只喂到开启，不留结束事件
        TraceComposer.Session session = collector.openSession("u1:s1");
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        collector.accept(session, new AgentStartEvent("sess", "r1", "agent-a"), ctx);
        collector.accept(session, new ModelCallStartEvent("r1"), ctx);

        collector.close(session);

        assertThat(store.read("u1:s1").stream().filter(ReasoningSpan::isClosed))
                .as("收尾必须为未关闭节点补关闭快照，避免轨迹里出现无终点的节点")
                .isNotEmpty();
    }

    @Test
    @DisplayName("无会话身份：openSession 返回 null，归集被跳过")
    void skipsWhenNoTraceId() {
        assertThat(collector.openSession(null)).isNull();

        // 传入 null 会话时归集应无操作且不抛异常
        collector.accept(null, new AgentStartEvent("sess", "r1", "agent-a"), null);
        collector.close(null);

        assertThat(store.read("u1:s1")).isEmpty();
    }

    @Test
    @DisplayName("注入节点：编排层产生的意图节点可挂入轨迹")
    void injectsOrchestrationSpan() {
        TraceComposer.Session session = collector.openSession("u1:s1");
        collector.inject(session, SpanKind.INTENT, java.util.Map.of("intentLabel", "问诊"), 12L);
        collector.close(session);

        assertThat(store.read("u1:s1"))
                .extracting(ReasoningSpan::kind)
                .contains(SpanKind.INTENT);
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

            @Override
            public List<String> recentTraceIds(int limit) {
                return List.of();
            }

            @Override
            public String storeEndpoint() {
                return "(测试用故障存储)";
            }
        };
        TraceCollector resilient = new TraceCollector(new TraceComposer(TestCodecs.newCodec()), failing);

        TraceComposer.Session session = resilient.openSession("u1:s1");
        // 助手：存储故障时归集不得向调用方抛出
        resilient.accept(session, new AgentStartEvent("sess", "r1", "agent-a"), null);
        resilient.close(session);
    }
}
