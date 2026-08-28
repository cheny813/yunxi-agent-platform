package io.yunxi.platform.execution.interceptors;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.shared.dto.ConfirmResultRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 人机确认（HITL）结果回传拦截器测试。
 *
 * <p>验证请求中的 {@link ConfirmResultRequest} 被正确转换为 AgentScope 的
 * {@link ConfirmResult} 并写入输入消息元数据，且不影响原有元数据与消息内容。
 */
@DisplayName("HITL 确认结果回传拦截器")
class HITLConfirmInterceptorTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String USER_ID = "user-1";
    private static final String AGENT_NAME = "agent-a";

    private final HITLConfirmInterceptor interceptor = new HITLConfirmInterceptor();

    private ConfirmResultRequest confirm(String toolCallId, boolean approved) {
        ConfirmResultRequest req = new ConfirmResultRequest();
        req.setToolCallId(toolCallId);
        req.setApproved(approved);
        req.setToolName("execute");
        Map<String, Object> input = new HashMap<>();
        input.put("command", "echo hi");
        req.setInput(input);
        return req;
    }

    private ExecutionContext contextWith(List<ConfirmResultRequest> confirmResults, Msg userMsg) {
        ExecutionRequest request = ExecutionRequest.builder()
                .message("继续")
                .agentName(AGENT_NAME)
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .confirmResults(confirmResults)
                .build();
        ExecutionContext ctx =
                new ExecutionContext(request, CONVERSATION_ID, USER_ID, AGENT_NAME);
        ctx.setInputMessage(userMsg);
        return ctx;
    }

    private Msg userMessage(String text, Map<String, Object> metadata) {
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(text)
                .metadata(metadata)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<ConfirmResult> resultsOf(ExecutionContext ctx) {
        Map<String, Object> md = ctx.getInputMessage().getMetadata();
        assertThat(md).containsKey(Msg.METADATA_CONFIRM_RESULTS);
        return (List<ConfirmResult>) md.get(Msg.METADATA_CONFIRM_RESULTS);
    }

    @Test
    @DisplayName("无确认结果时不改动输入消息（行为与原先一致）")
    void noConfirmResultsKeepsMessageUntouched() {
        Msg original = userMessage("你好", null);
        ExecutionContext ctx = contextWith(null, original);

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage()).isSameAs(original);
    }

    @Test
    @DisplayName("空列表同样不改动输入消息")
    void emptyListKeepsMessageUntouched() {
        Msg original = userMessage("你好", null);
        ExecutionContext ctx = contextWith(List.of(), original);

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage()).isSameAs(original);
    }

    @Test
    @DisplayName("确认结果写入 METADATA_CONFIRM_RESULTS 且字段正确")
    void injectsConfirmResults() {
        ExecutionContext ctx = contextWith(
                List.of(confirm("call-1", true), confirm("call-2", false)),
                userMessage("继续", null));

        interceptor.preHandle(ctx);

        List<ConfirmResult> results = resultsOf(ctx);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getToolCall().getId()).isEqualTo("call-1");
        assertThat(results.get(0).isConfirmed()).isTrue();
        assertThat(results.get(1).getToolCall().getId()).isEqualTo("call-2");
        assertThat(results.get(1).isConfirmed()).isFalse();
    }

    @Test
    @DisplayName("保留消息原有元数据（追加而非覆盖）")
    void preservesExistingMetadata() {
        Map<String, Object> existing = new HashMap<>();
        existing.put("custom-key", "custom-value");
        ExecutionContext ctx = contextWith(List.of(confirm("call-1", true)),
                userMessage("继续", existing));

        interceptor.preHandle(ctx);

        Map<String, Object> md = ctx.getInputMessage().getMetadata();
        assertThat(md).containsEntry("custom-key", "custom-value");
        assertThat(md).containsKey(Msg.METADATA_CONFIRM_RESULTS);
    }

    @Test
    @DisplayName("保留消息正文内容")
    void preservesMessageContent() {
        ExecutionContext ctx = contextWith(List.of(confirm("call-1", true)),
                userMessage("原始内容", null));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage().getTextContent()).isEqualTo("原始内容");
    }

    @Test
    @DisplayName("缺少 toolCallId 的项被跳过，不中断整轮恢复")
    void skipsItemsWithoutToolCallId() {
        ConfirmResultRequest invalid = new ConfirmResultRequest();
        invalid.setApproved(true);
        ExecutionContext ctx = contextWith(
                List.of(invalid, confirm("call-1", true)),
                userMessage("继续", null));

        interceptor.preHandle(ctx);

        List<ConfirmResult> results = resultsOf(ctx);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getToolCall().getId()).isEqualTo("call-1");
    }

    @Test
    @DisplayName("全部项均无效时不注入元数据")
    void noValidItemsThenNoMetadata() {
        ConfirmResultRequest blank = new ConfirmResultRequest();
        blank.setToolCallId("   ");
        Msg original = userMessage("继续", null);
        ExecutionContext ctx = contextWith(List.of(blank), original);

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage()).isSameAs(original);
    }

    @Test
    @DisplayName("输入消息为空时安全跳过（不抛异常）")
    void handlesMissingInputMessage() {
        ExecutionRequest request = ExecutionRequest.builder()
                .message("继续")
                .agentName(AGENT_NAME)
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .confirmResults(List.of(confirm("call-1", true)))
                .build();
        ExecutionContext ctx =
                new ExecutionContext(request, CONVERSATION_ID, USER_ID, AGENT_NAME);

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage()).isNull();
    }

    @Test
    @DisplayName("执行顺序位于 Memory(150) 之后、IntentPipeline(200) 之前")
    void orderIsBetweenMemoryAndIntentPipeline() {
        assertThat(interceptor.getOrder()).isEqualTo(175);
    }
}
