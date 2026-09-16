package io.yunxi.platform.trace.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.trace.ReasoningSpan;
import io.yunxi.platform.trace.SpanKind;
import io.agentscope.core.message.ToolResultState;

import org.junit.jupiter.api.Test;

/**
 * M5 AguiProjection 映射测试：覆盖 §6 五族同构 + §6.1 反压定案的主要种类。
 *
 * @author yunxi-agent-platform
 */
class AguiProjectionTest {

    private final AguiProjection projection = new AguiProjection();
    private final ObjectMapper mapper = new ObjectMapper();

    private String firstType(ReasoningSpan span) {
        List<String> events = projection.convert(span, ProjectionContext.of("t1", null, null, Map.of()));
        assertFalse(events.isEmpty(), "expected at least one AG-UI event");
        try {
            JsonNode node = mapper.readTree(events.get(0));
            return node.get("type").asText();
        } catch (Exception e) {
            throw new AssertionError("invalid JSON: " + events.get(0), e);
        }
    }

    private String typeAt(ReasoningSpan span, int index) {
        List<String> events = projection.convert(span, ProjectionContext.of("t1", null, null, Map.of()));
        try {
            return mapper.readTree(events.get(index)).get("type").asText();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void turnLifecycle() {
        assertEquals("RUN_STARTED", firstType(ReasoningSpan.open("t1", "TURN|-|TURN|r1", "e1", SpanKind.TURN, null)));
        ReasoningSpan ok = ReasoningSpan.closed("t1", "TURN|-|TURN|r1", "e2", SpanKind.TURN, ToolResultState.SUCCESS, 10L, Map.of());
        assertEquals("RUN_FINISHED", firstType(ok));
        assertTrue(projection.convert(ok, ProjectionContext.of("t1", null, null, Map.of())).get(0).contains("\"outcome\":\"success\""));
        ReasoningSpan err = ReasoningSpan.closed("t1", "TURN|-|TURN|r1", "e3", SpanKind.TURN, ToolResultState.ERROR, 10L, Map.of("result", "boom"));
        assertEquals("RUN_ERROR", firstType(err));
        ReasoningSpan interrupted = ReasoningSpan.closed("t1", "TURN|-|TURN|r1", "e4", SpanKind.TURN, ToolResultState.INTERRUPTED, 10L, Map.of());
        assertEquals("RUN_FINISHED", firstType(interrupted));
        assertTrue(projection.convert(interrupted, ProjectionContext.of("t1", null, null, Map.of())).get(0).contains("\"outcome\":\"interrupted\""));
    }

    @Test
    void textBlock() {
        String id = "TEXT|-|BLOCK_ID|m1";
        assertEquals("TEXT_MESSAGE_START", firstType(ReasoningSpan.open("t1", id, "e1", SpanKind.TEXT, null)));
        assertEquals("TEXT_MESSAGE_CONTENT", firstType(ReasoningSpan.delta("t1", id, "e2", SpanKind.TEXT, "hello")));
        assertEquals("TEXT_MESSAGE_END", firstType(ReasoningSpan.closed("t1", id, "e3", SpanKind.TEXT, null, null, null)));
    }

    @Test
    void reasoningBlock() {
        String id = "REASONING|-|BLOCK_ID|m2";
        assertEquals("REASONING_MESSAGE_START", firstType(ReasoningSpan.open("t1", id, "e1", SpanKind.REASONING, null)));
        assertEquals("REASONING_MESSAGE_CONTENT", firstType(ReasoningSpan.delta("t1", id, "e2", SpanKind.REASONING, "thinking")));
        assertEquals("REASONING_MESSAGE_END", firstType(ReasoningSpan.closed("t1", id, "e3", SpanKind.REASONING, null, null, null)));
    }

    @Test
    void toolCallLifecycle() {
        String id = "TOOL_CALL|-|TOOL_CALL_ID|c1";
        Map<String, Object> open = Map.of("toolCallId", "c1", "toolName", "search");
        assertEquals("TOOL_CALL_START", firstType(ReasoningSpan.open("t1", id, "e1", SpanKind.TOOL_CALL, null).withPayload(open)));
        // 参数增量（resultPhase 缺失）
        ReasoningSpan argDelta = ReasoningSpan.delta("t1", id, "e2", SpanKind.TOOL_CALL, "{\"q\":");
        assertEquals("TOOL_CALL_ARGS", firstType(argDelta.withPayload(Map.of("toolCallId", "c1"))));
        // 结果增量
        ReasoningSpan resDelta = ReasoningSpan.delta("t1", id, "e3", SpanKind.TOOL_CALL, "ok");
        assertEquals("TOOL_CALL_RESULT", firstType(resDelta.withPayload(Map.of("toolCallId", "c1", "resultPhase", "delta"))));
        // 关闭（结果终态）
        assertEquals("TOOL_CALL_END", firstType(ReasoningSpan.closed("t1", id, "e4", SpanKind.TOOL_CALL, null, null,
                Map.of("toolCallId", "c1", "toolName", "search", "resultPhase", "end", "result", "final"))));
    }

    @Test
    void statePlanSnapshot() {
        String id = "PLAN|-|PLAN|p1";
        Map<String, Object> payload = Map.of("tasks", List.of(Map.of("name", "a")), "taskCount", 1);
        assertEquals("STATE_SNAPSHOT", firstType(ReasoningSpan.closed("t1", id, "e1", SpanKind.PLAN, null, null, payload)));
    }

    @Test
    void subagentStart() {
        String id = "SUBAGENT|-|SUBAGENT|s1";
        Map<String, Object> payload = Map.of("label", "researcher", "subagentId", "s1");
        assertEquals("SUBAGENT_STARTED", firstType(ReasoningSpan.open("t1", id, "e1", SpanKind.SUBAGENT, null).withPayload(payload)));
    }

    @Test
    void hitlPauseResume() {
        String id = "HITL|-|HITL|h1";
        Map<String, Object> payload = Map.of("toolCalls", List.of(Map.of("name", "send_email")));
        assertEquals("RUN_PAUSED", firstType(ReasoningSpan.open("t1", id, "e1", SpanKind.HITL, null).withPayload(payload)));
        assertEquals("RUN_RESUMED", firstType(ReasoningSpan.closed("t1", id, "e2", SpanKind.HITL, ToolResultState.INTERRUPTED, 10L, Map.of())));
    }

    @Test
    void controlStep() {
        String id = "CONTROL|-|CONTROL|x1";
        assertEquals("STEP_FINISHED", firstType(ReasoningSpan.closed("t1", id, "e1", SpanKind.CONTROL, null, null, Map.of("reason", "max_iters"))));
    }

    @Test
    void modelCallUsagePlaceholder() {
        String id = "MODEL_CALL|-|MODEL_CALL|mc1";
        Map<String, Object> payload = Map.of("model", "gpt-4o", "provider", "openai", "usage", Map.of("promptTokens", 10));
        assertEquals("CUSTOM", firstType(ReasoningSpan.closed("t1", id, "e1", SpanKind.MODEL_CALL, null, null, payload)));
        String json = projection.convert(ReasoningSpan.closed("t1", id, "e1", SpanKind.MODEL_CALL, null, null, payload),
                ProjectionContext.of("t1", null, null, Map.of())).get(0);
        assertTrue(json.contains("\"name\":\"model_call_usage\""), "expected model_call_usage placeholder");
        assertTrue(json.contains("\"model\":\"gpt-4o\""), "expected model attributed (A′)");
    }

    @Test
    void customAndData() {
        String id = "CUSTOM|-|CUSTOM|c1";
        Map<String, Object> payload = Map.of("name", "metric", "value", Map.of("x", 1));
        assertEquals("CUSTOM", firstType(ReasoningSpan.closed("t1", id, "e1", SpanKind.CUSTOM, null, null, payload)));
        String dataId = "DATA|-|DATA|d1";
        assertTrue(projection.convert(ReasoningSpan.delta("t1", dataId, "e2", SpanKind.DATA, "blob"),
                ProjectionContext.of("t1", null, null, Map.of())).get(0).contains("\"name\":\"data\""));
    }

    @Test
    void intentNotProjected() {
        String id = "INTENT|-|INTENT|i1";
        assertTrue(projection.convert(ReasoningSpan.closed("t1", id, "e1", SpanKind.INTENT, null, null, Map.of("intent", "x")),
                ProjectionContext.of("t1", null, null, Map.of())).isEmpty(), "INTENT must not emit AG-UI events");
    }

    @Test
    void toolCallEndThenResultOnClose() {
        String id = "TOOL_CALL|-|TOOL_CALL_ID|c2";
        List<String> events = projection.convert(
                ReasoningSpan.closed("t1", id, "e1", SpanKind.TOOL_CALL, null, null,
                        Map.of("toolCallId", "c2", "toolName", "db", "resultPhase", "end", "result", "rows")),
                ProjectionContext.of("t1", null, null, Map.of()));
        assertEquals(2, events.size(), "close with final result emits TOOL_CALL_END + TOOL_CALL_RESULT");
        assertEquals("TOOL_CALL_END", typeAt(ReasoningSpan.closed("t1", id, "e1", SpanKind.TOOL_CALL, null, null,
                Map.of("toolCallId", "c2", "toolName", "db", "resultPhase", "end", "result", "rows")), 0));
    }
}
