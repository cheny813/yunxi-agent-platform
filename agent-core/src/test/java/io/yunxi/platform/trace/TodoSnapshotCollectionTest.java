package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.TaskContextState;
import reactor.core.publisher.Flux;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务清单归集测试：{@code todo_write} 调用的清单快照入树。
 *
 * <p>本类守护的约束是 M4 的一项验收：<b>清单内容不在事件流里，必须从 AgentState 旁路读取</b>。
 * 这一点很反直觉 —— {@code todo_write} 明明是工具调用，事件流里也有它的
 * {@code TOOL_CALL_START}，但该事件<b>只带标识与名称、不带工具参数</b>，
 * 模型提交的完整清单落在 {@code AgentState.tasksContext}。若只依赖事件归集，
 * 轨迹里会有一个「调用了 todo_write」的空壳节点，而看不到模型到底打算做什么。</p>
 */
@DisplayName("任务清单归集")
class TodoSnapshotCollectionTest {

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

    /** 构造带清单的 AgentState。 */
    private static AgentState stateWithTasks() {
        TaskContextState tasks = new TaskContextState();
        tasks.tasksMutable().add(Task.builder()
                .id("t1").subject("读取配置").description("读取 application.yml")
                .state(Task.State.COMPLETED).build());
        tasks.tasksMutable().add(Task.builder()
                .id("t2").subject("生成报告").description("汇总结果并输出")
                .state(Task.State.IN_PROGRESS).build());
        return AgentState.builder().sessionId("s1").userId("u1").tasksContext(tasks).build();
    }

    private void run(String toolName, AgentState state) {
        RuntimeContext.Builder b = RuntimeContext.builder().userId("u1").sessionId("s1");
        if (state != null) {
            b.agentState(state);
        }
        Flux<AgentEvent> events = Flux.just(
                new AgentStartEvent("s1", "r1", "agent-a"),
                new ToolCallStartEvent("r1", "call-1", toolName),
                new AgentEndEvent("r1"));
        Function<AgentInput, Flux<AgentEvent>> next = input -> events;
        middleware.onAgent(agentNamed("agent-a"), b.build(), new AgentInput(List.of()), next)
                .collectList()
                .block();
    }

    @Test
    @DisplayName("todo_write 调用：清单快照注入为 PLAN 节点")
    void todoWriteProducesPlanSpan() {
        run("todo_write", stateWithTasks());

        List<ReasoningSpan> plans = store.read("u1:s1").stream()
                .filter(s -> s.kind() == SpanKind.PLAN)
                .toList();

        assertThat(plans)
                .as("清单不在事件流里，必须旁路读取后注入")
                .isNotEmpty();
    }

    @Test
    @DisplayName("清单负载含任务项与状态（回答「模型这次打算做什么」）")
    void planSpanCarriesTaskList() {
        run("todo_write", stateWithTasks());

        ReasoningSpan open = store.read("u1:s1").stream()
                .filter(s -> s.kind() == SpanKind.PLAN && s.isOpen())
                .findFirst()
                .orElseThrow();

        assertThat(open.payload()).containsEntry("taskCount", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) open.payload().get("tasks");
        assertThat(tasks)
                .as("应携带完整清单，含标识、主题与状态")
                .hasSize(2)
                .anySatisfy(t -> assertThat(t)
                        .containsEntry("id", "t1")
                        .containsEntry("subject", "读取配置")
                        .containsEntry("state", "COMPLETED"))
                .anySatisfy(t -> assertThat(t).containsEntry("state", "IN_PROGRESS"));
    }

    @Test
    @DisplayName("清单节点与触发它的工具调用可配对")
    void planSpanPairsWithToolCall() {
        run("todo_write", stateWithTasks());

        ReasoningSpan plan = store.read("u1:s1").stream()
                .filter(s -> s.kind() == SpanKind.PLAN && s.isOpen())
                .findFirst()
                .orElseThrow();

        assertThat(plan.payload())
                .as("清单节点应带触发它的工具调用标识，供「哪次写入产生了这张清单」追溯")
                .containsEntry("toolCallId", "call-1");
    }

    @Test
    @DisplayName("非 todo_write 工具不注入清单节点（不制造空壳）")
    void otherToolDoesNotProducePlanSpan() {
        run("calculator", stateWithTasks());

        assertThat(store.read("u1:s1").stream().filter(s -> s.kind() == SpanKind.PLAN))
                .as("只有清单写入工具才产生清单节点")
                .isEmpty();
    }

    @Test
    @DisplayName("无 AgentState 时不注入且不中断执行（降级安全）")
    void missingStateDegradesSafely() {
        run("todo_write", null);

        assertThat(store.read("u1:s1").stream().filter(s -> s.kind() == SpanKind.PLAN))
                .as("取不到状态则跳过，不留半成品节点")
                .isEmpty();
        assertThat(store.read("u1:s1"))
                .as("事件流仍应正常归集")
                .isNotEmpty();
    }

    @Test
    @DisplayName("清单节点不入栈：后续节点归属不受影响")
    void planInjectionDoesNotBreakTree() {
        run("todo_write", stateWithTasks());

        ReasoningSpan tool = store.read("u1:s1").stream()
                .filter(s -> s.kind() == SpanKind.TOOL_CALL && s.isOpen())
                .findFirst()
                .orElseThrow();

        assertThat(tool.parentKey())
                .as("工具调用仍应挂在回合节点下 —— 注入的清单节点若入栈会把它抢走")
                .isNotNull()
                .isNotEqualTo(store.read("u1:s1").stream()
                        .filter(s -> s.kind() == SpanKind.PLAN)
                        .map(ReasoningSpan::stableKey)
                        .findFirst()
                        .orElse(""));
    }
}
