package io.yunxi.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.TaskContextState;
import io.yunxi.platform.conversation.ConversationDomainService;
import io.yunxi.platform.shared.util.SseMessageBuilder;
import io.yunxi.platform.tracing.LlmMetrics;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务清单（TodoList）事件透出测试。
 *
 * <p>重点验证 {@code resolveTaskList} 按 (userId, sessionId) <b>会话槽</b>取 AgentState，
 * 而非回退到无参 {@code getAgentState()}（该方法内部返回默认槽位，多会话并发时会串号）。
 */
@DisplayName("任务清单事件透出（SseAgentEventAdapter）")
class SseAgentEventAdapterTodoListTest {

    private static final String USER_ID = "user-1";
    private static final String CONVERSATION_ID = "conv-1";

    private SseAgentEventAdapter adapter;
    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        adapter = new SseAgentEventAdapter(
                new SseMessageBuilder(), mock(LlmMetrics.class), mock(ConversationDomainService.class));
        agent = mock(ReActAgent.class);
    }

    private ExecutionContext newContext() {
        ExecutionRequest request = ExecutionRequest.builder()
                .message("生成方案")
                .agentName("agent-a")
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .build();
        ExecutionContext ctx =
                new ExecutionContext(request, request.getConversationId(), request.getUserId(),
                        request.getAgentName());
        ctx.setResolvedAgent(agent);
        return ctx;
    }

    /** 构造含指定任务的 AgentState（AgentState 为 final，只能用 Builder 构造） */
    private AgentState stateWithTasks(Task... tasks) {
        TaskContextState tc = new TaskContextState();
        tc.tasksMutable().addAll(List.of(tasks));
        return AgentState.builder().tasksContext(tc).build();
    }

    private Task task(String subject, Task.State state) {
        return Task.builder().subject(subject).description(subject).state(state).build();
    }

    /**
     * content 为 JSON 字符串（与 tool_result 等结构化事件同一编码惯例），取出后二次解析。
     */
    private String payloadOf(String sseMessage) throws Exception {
        String json = sseMessage.substring(sseMessage.indexOf('{'));
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(json).get("content").asText();
    }

    @Test
    @DisplayName("会话恢复：onStart 补发历史任务清单（读会话槽，非默认槽）")
    void onStartRestoresTaskListFromSessionSlot() throws Exception {
        AgentState slotState =
                stateWithTasks(task("查询营养成分", Task.State.COMPLETED), task("生成配餐方案",
                        Task.State.IN_PROGRESS));
        // 会话槽有任务；默认槽位（无参 getAgentState）为空 —— 若实现错误地回退到默认槽，
        // 则不会补发 todo_update，即可暴露串号缺陷
        when(agent.getAgentState(USER_ID, CONVERSATION_ID)).thenReturn(slotState);
        when(agent.getAgentState()).thenReturn(AgentState.builder().build());

        List<String> messages = adapter.onStart(newContext());

        assertThat(messages).anyMatch(m -> m.contains("\"type\":\"todo_update\""));
        String todoMsg = messages.stream()
                .filter(m -> m.contains("todo_update"))
                .findFirst()
                .orElseThrow();
        String payload = payloadOf(todoMsg);
        assertThat(payload).contains("查询营养成分").contains("生成配餐方案");
        assertThat(payload).contains("\"state\":\"completed\"");
        assertThat(payload).contains("\"state\":\"in_progress\"");
        assertThat(todoMsg).contains(CONVERSATION_ID);
    }

    @Test
    @DisplayName("会话恢复：使用带会话身份的重载，不调用已废弃的无参 getAgentState")
    void onStartDoesNotUseDeprecatedNoArgGetAgentState() throws Exception {
        when(agent.getAgentState(USER_ID, CONVERSATION_ID))
                .thenReturn(stateWithTasks(task("任务A", Task.State.PENDING)));
        when(agent.getAgentState()).thenReturn(stateWithTasks(task("默认槽任务", Task.State.PENDING)));

        List<String> messages = adapter.onStart(newContext());

        String todoMsg = messages.stream()
                .filter(m -> m.contains("todo_update"))
                .findFirst()
                .orElseThrow();
        String payload = payloadOf(todoMsg);
        assertThat(payload).contains("任务A");
        // 关键断言：若回退到默认槽，这里会出现"默认槽任务"
        assertThat(payload).doesNotContain("默认槽任务");
    }

    @Test
    @DisplayName("会话无历史任务：onStart 不补发 todo_update")
    void onStartSkipsWhenNoTasks() {
        when(agent.getAgentState(USER_ID, CONVERSATION_ID)).thenReturn(AgentState.builder().build());

        List<String> messages = adapter.onStart(newContext());

        assertThat(messages).noneMatch(m -> m.contains("todo_update"));
        assertThat(messages).hasSize(1); // 仅 start 事件
    }

    @Test
    @DisplayName("todo_write 工具结束后广播 todo_update")
    void broadcastOnTodoWriteResult() throws Exception {
        when(agent.getAgentState(USER_ID, CONVERSATION_ID))
                .thenReturn(stateWithTasks(task("步骤一", Task.State.COMPLETED)));

        List<String> messages = adapter.convert(
                new ToolResultEndEvent("reply-1", "call-1", "todo_write", ToolResultState.SUCCESS),
                newContext());

        String todoMsg = messages.stream()
                .filter(m -> m.contains("todo_update"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未广播 todo_update"));
        assertThat(payloadOf(todoMsg)).contains("步骤一");
    }

    @Test
    @DisplayName("非 todo_write 工具不广播任务清单")
    void noBroadcastForOtherTools() {
        when(agent.getAgentState(USER_ID, CONVERSATION_ID))
                .thenReturn(stateWithTasks(task("步骤一", Task.State.COMPLETED)));

        List<String> messages = adapter.convert(
                new ToolResultEndEvent("reply-1", "call-1", "query_database", ToolResultState.SUCCESS),
                newContext());

        assertThat(messages).noneMatch(m -> m.contains("todo_update"));
    }

    @Test
    @DisplayName("Agent 未解析：降级为空，不抛异常也不广播")
    void degradeWhenAgentNotResolved() {
        ExecutionContext ctx = newContext();
        ctx.setResolvedAgent(null);

        List<String> messages = adapter.onStart(ctx);
        List<String> toolMessages = adapter.convert(
                new ToolResultEndEvent("reply-1", "call-1", "todo_write", ToolResultState.SUCCESS), ctx);

        assertThat(messages).noneMatch(m -> m.contains("todo_update"));
        assertThat(toolMessages).isNotNull();
    }

    @Test
    @DisplayName("任务清单使用下划线序列化字段（created_at / blocked_by）")
    void serializedFieldNames() throws Exception {
        when(agent.getAgentState(USER_ID, CONVERSATION_ID))
                .thenReturn(stateWithTasks(task("步骤一", Task.State.PENDING)));

        List<String> messages = adapter.onStart(newContext());
        String todoMsg = messages.stream()
                .filter(m -> m.contains("todo_update"))
                .findFirst()
                .orElseThrow();

        String payload = payloadOf(todoMsg);
        assertThat(payload).contains("\"created_at\"");
        assertThat(payload).contains("\"blocked_by\"");
        assertThat(payload).doesNotContain("\"createdAt\"");
    }
}
