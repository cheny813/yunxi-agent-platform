package io.yunxi.platform.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.state.Task;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务清单事件（todo_update）的消息格式测试。
 *
 * <p>契约要点：{@code type=todo_update}；负载在 {@code content} 字段（不是 data）；
 * {@code content.todos} 为全量任务数组；序列化字段名为下划线风格（created_at / blocked_by）。
 */
@DisplayName("任务清单 SSE 事件格式（SseMessageBuilder）")
class SseMessageBuilderTodoListTest {

    private final SseMessageBuilder builder = new SseMessageBuilder();

    private Task task(String subject, Task.State state) {
        return Task.builder().subject(subject).description(subject).state(state).build();
    }

    /** 与 tool_result 等结构化事件同一编码惯例：content 为 JSON 字符串，需前端二次解析 */
    private String payloadOf(String sseMessage) throws Exception {
        // 方法返回的是 JSON 字符串本身；SSE 的 "data: " 前缀由 Spring 在写出时封装
        String json = sseMessage.substring(sseMessage.indexOf('{'));
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(json).get("content").asText();
    }

    @Test
    @DisplayName("带会话ID：输出 todo_update 类型与会话ID")
    void buildWithConversationId() throws Exception {
        String msg =
                builder.buildTodoUpdateMessage(List.of(task("步骤一", Task.State.PENDING)), "conv-1");

        assertThat(msg).startsWith("{");
        assertThat(msg).contains("\"type\":\"todo_update\"");
        assertThat(msg).contains("\"conversationId\":\"conv-1\"");
        assertThat(payloadOf(msg)).contains("步骤一");
    }

    @Test
    @DisplayName("无会话ID：不输出 conversationId 字段")
    void buildWithoutConversationId() {
        String msg = builder.buildTodoUpdateMessage(List.of(task("步骤一", Task.State.PENDING)), null);

        assertThat(msg).contains("\"type\":\"todo_update\"");
        assertThat(msg).doesNotContain("conversationId");
    }

    @Test
    @DisplayName("空清单：输出空 todos 数组而非报错")
    void buildWithEmptyList() throws Exception {
        String msg = builder.buildTodoUpdateMessage(List.of(), "conv-1");
        assertThat(payloadOf(msg)).contains("\"todos\":[]");

        String nullMsg = builder.buildTodoUpdateMessage(null, "conv-1");
        assertThat(payloadOf(nullMsg)).contains("\"todos\":[]");
    }

    @Test
    @DisplayName("三态 wire 值：pending / in_progress / completed")
    void stateWireValues() throws Exception {
        String msg = builder.buildTodoUpdateMessage(
                List.of(
                        task("待办", Task.State.PENDING),
                        task("进行中", Task.State.IN_PROGRESS),
                        task("已完成", Task.State.COMPLETED)),
                "conv-1");

        String payload = payloadOf(msg);
        assertThat(payload).contains("\"state\":\"pending\"");
        assertThat(payload).contains("\"state\":\"in_progress\"");
        assertThat(payload).contains("\"state\":\"completed\"");
        // 不应输出 Java 枚举名
        assertThat(payload).doesNotContain("\"PENDING\"");
        assertThat(payload).doesNotContain("\"IN_PROGRESS\"");
    }

    @Test
    @DisplayName("下划线字段：created_at / blocked_by（非 camelCase）")
    void underscoreFieldNames() throws Exception {
        String msg = builder.buildTodoUpdateMessage(List.of(task("步骤一", Task.State.PENDING)), null);

        String payload = payloadOf(msg);
        assertThat(payload).contains("\"created_at\"");
        assertThat(payload).contains("\"blocked_by\"");
        assertThat(payload).doesNotContain("\"createdAt\"");
        assertThat(payload).doesNotContain("\"blockedBy\"");
    }

    @Test
    @DisplayName("content 为 JSON 字符串（与 tool_result 等结构化事件同一编码惯例）")
    void payloadIsJsonString() throws Exception {
        String msg = builder.buildTodoUpdateMessage(List.of(task("步骤一", Task.State.PENDING)), null);

        // content 是字符串而非嵌套对象，前端需二次解析
        String payload = payloadOf(msg);
        assertThat(payload).startsWith("{");
        // 解析后即为含 todos 的对象
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        assertThat(node.get("todos").isArray()).isTrue();
        assertThat(node.get("todos").get(0).get("subject").asText()).isEqualTo("步骤一");
    }
}
