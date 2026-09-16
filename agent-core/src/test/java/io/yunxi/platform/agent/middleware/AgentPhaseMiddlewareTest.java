package io.yunxi.platform.agent.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.middleware.AgentInput;
import io.yunxi.platform.execution.AgentPhase;
import reactor.core.publisher.Flux;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentPhaseMiddleware} 单元测试：阶段推导、标记注入与轨迹记录。
 *
 * <p>本类由 {@code AgentPhaseTracker}（引擎内联算子）迁移而来。迁移的同时补齐了原测试缺失的
 * 覆盖：原实现按 {@code agentName} 单键记录轨迹，无法区分同一 Agent 上的不同会话；新实现按
 * {@code sessionId} 主键、{@code agentName} 回退的双键查找。此处既锁住阶段推导的纯函数语义
 * （迁移不得改变行为），也锁住新增的会话隔离能力。</p>
 */
@DisplayName("AgentPhaseMiddleware 阶段归集")
class AgentPhaseMiddlewareTest {

    private final AgentPhaseMiddleware middleware = new AgentPhaseMiddleware();

    /** 构造带名字的 Agent stub，用于验证 agentName 回退键。 */
    private static Agent agentNamed(String name) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(name);
        return agent;
    }

    /** 执行一次 onAgent，返回注入阶段标记后的事件序列。 */
    private List<AgentEvent> run(Flux<AgentEvent> source, String agentName, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("u1")
                .build();
        Agent agent = agentNamed(agentName);
        Function<AgentInput, Flux<AgentEvent>> next = input -> source;
        return middleware.onAgent(agent, ctx, new AgentInput(List.of()), next)
                .collectList()
                .block();
    }

    /** 从事件序列中抽出注入的阶段标记名。 */
    private static List<String> phasesOf(List<AgentEvent> events) {
        List<String> phases = new ArrayList<>();
        for (AgentEvent e : events) {
            if (e instanceof CustomEvent ce
                    && AgentPhaseMiddleware.AGENT_STATUS_EVENT_NAME.equals(ce.getName())) {
                phases.add(String.valueOf(ce.getValue().get("phase")));
            }
        }
        return phases;
    }

    @Test
    @DisplayName("阶段推导：THINKING → TOOL_CALL → ANSWER，切换处各注入一条标记")
    void derivesPhasesOnTransition() {
        List<AgentEvent> out = run(Flux.just(
                        new ModelCallStartEvent("r1"),
                        new ToolCallStartEvent("r1", "c1", "read_file"),
                        new TextBlockStartEvent("r1", "b1"),
                        new AgentResultEvent(null)),
                "agent-a", "sess-1");

        assertThat(phasesOf(out)).containsExactly(
                AgentPhase.THINKING.name(),
                AgentPhase.TOOL_CALL.name(),
                AgentPhase.ANSWER.name(),
                AgentPhase.DONE.name());
    }

    @Test
    @DisplayName("同一阶段内重复事件只注入一次标记（阶段标记是切换信号，不是事件回声）")
    void injectsOncePerPhase() {
        List<AgentEvent> out = run(Flux.just(
                        new ToolCallStartEvent("r1", "c1", "a"),
                        new ToolCallStartEvent("r1", "c2", "b"),
                        new ToolCallStartEvent("r1", "c3", "c")),
                "agent-a", "sess-1");

        assertThat(phasesOf(out)).containsExactly(
                AgentPhase.TOOL_CALL.name(),
                AgentPhase.DONE.name());
    }

    @Test
    @DisplayName("流正常结束时补充 DONE 标记")
    void appendsDoneOnComplete() {
        List<AgentEvent> out = run(Flux.just(new AgentResultEvent(null)), "agent-a", "sess-1");

        assertThat(phasesOf(out))
                .containsExactly(AgentPhase.ANSWER.name(), AgentPhase.DONE.name());
    }

    @Test
    @DisplayName("空流：直接得到 DONE（不留悬空阶段）")
    void emptyStreamStillReachesDone() {
        List<AgentEvent> out = run(Flux.empty(), "agent-a", "sess-1");

        assertThat(phasesOf(out)).containsExactly(AgentPhase.DONE.name());
    }

    @Test
    @DisplayName("轨迹按会话隔离：两个会话各自记录，互不覆盖")
    void tracesIsolatedPerSession() {
        run(Flux.just(new ToolCallStartEvent("r1", "c1", "x")), "agent-a", "sess-1");
        run(Flux.just(new TextBlockStartEvent("r1", "b1")), "agent-a", "sess-2");

        // 用 containsExactly 而非 contains：轨迹末尾恒有 DONE，松散断言会让「被覆盖」也通过
        assertThat(middleware.getLastPhaseTrace("agent-a", "sess-1").trace())
                .as("sess-1 的轨迹应为 IDLE→TOOL_CALL→DONE，不得混入 sess-2 的 ANSWER")
                .containsExactly(AgentPhase.IDLE.name(),
                        AgentPhase.TOOL_CALL.name(),
                        AgentPhase.DONE.name());
        assertThat(middleware.getLastPhaseTrace("agent-a", "sess-2").trace())
                .as("sess-2 的轨迹应为 IDLE→ANSWER→DONE，不得混入 sess-1 的 TOOL_CALL")
                .containsExactly(AgentPhase.IDLE.name(),
                        AgentPhase.ANSWER.name(),
                        AgentPhase.DONE.name());
    }

    @Test
    @DisplayName("无会话身份时回退按 agentName 查询")
    void fallsBackToAgentNameKey() {
        run(Flux.just(new ToolCallStartEvent("r1", "c1", "x")), "agent-fallback", null);

        assertThat(middleware.getLastPhaseTrace("agent-fallback"))
                .as("无 sessionId 时以 agentName 为键，旧签名仍可查到")
                .isNotNull();
        assertThat(middleware.getLastPhaseTrace("agent-fallback").trace())
                .containsExactly(AgentPhase.IDLE.name(),
                        AgentPhase.TOOL_CALL.name(),
                        AgentPhase.DONE.name());
    }

    @Test
    @DisplayName("未执行过的会话返回 null，不抛异常")
    void unknownSessionReturnsNull() {
        assertThat(middleware.getLastPhaseTrace("never-ran", "no-such-session")).isNull();
        assertThat(middleware.getLastPhaseTrace(null, null)).isNull();
    }

    @Test
    @DisplayName("轨迹以本会话的 stage 序列完整覆盖，不跨会话累积")
    void traceIsSnapshotNotAccumulation() {
        run(Flux.just(new ToolCallStartEvent("r1", "c1", "x")), "agent-a", "sess-x");
        run(Flux.just(new TextBlockStartEvent("r1", "b1")), "agent-a", "sess-x");

        assertThat(middleware.getLastPhaseTrace("agent-a", "sess-x").trace())
                .as("第二次执行应整体替换第一次的轨迹")
                .doesNotContain(AgentPhase.TOOL_CALL.name());
    }

    @Test
    @DisplayName("order 高于默认值：观测件贴近洋葱外层")
    void runsOnOuterOnion() {
        assertThat(middleware.order()).isGreaterThan(1);
    }
}
