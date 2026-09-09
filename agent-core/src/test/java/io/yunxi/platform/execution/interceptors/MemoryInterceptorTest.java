package io.yunxi.platform.execution.interceptors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.dto.ConfirmResultRequest;

/**
 * MemoryInterceptor（order=150）：记忆模式分支与上下文注入测试。
 */
@DisplayName("MemoryInterceptor 记忆组装")
class MemoryInterceptorTest {

    private final MemoryInterceptor interceptor = new MemoryInterceptor();

    private ExecutionContext ctxWith(ExecutionRequest.Builder builder) {
        ExecutionRequest request = builder.agentName("agent-a").build();
        return new ExecutionContext(request, null, null, "agent-a");
    }

    private Msg userMsg(String text) {
        return Msg.builder().textContent(text).build();
    }

    @Test
    @DisplayName("QUICK 模式：仅单条用户消息")
    void quickModeSingleMessage() {
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("快速问题")
                .quickMode(true)
                .memoryConfig(new MemoryConfig("smart"))
                .includeHistory(true)
                .historyMessages(List.of(userMsg("历史1"), userMsg("历史2"))));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessages()).hasSize(1);
        assertThat(ctx.getInputMessage().getTextContent()).isEqualTo("快速问题");
    }

    @Test
    @DisplayName("NONE 模式：仅单条用户消息")
    void noneModeSingleMessage() {
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("无记忆问题")
                .memoryConfig(new MemoryConfig("none"))
                .includeHistory(true)
                .historyMessages(List.of(userMsg("历史1"))));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessages()).hasSize(1);
        assertThat(ctx.getInputMessage().getTextContent()).isEqualTo("无记忆问题");
    }

    @Test
    @DisplayName("memoryConfig 为 null：按 NONE 处理，单条消息")
    void nullMemoryConfigSingleMessage() {
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("问题")
                .includeHistory(true));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessages()).hasSize(1);
    }

    @Test
    @DisplayName("includeHistory=false：单条消息")
    void noHistorySingleMessage() {
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("问题")
                .memoryConfig(new MemoryConfig("smart"))
                .includeHistory(false)
                .historyMessages(List.of(userMsg("历史1"))));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessages()).hasSize(1);
    }

    @Test
    @DisplayName("SMART 模式：历史消息 + 当前用户消息")
    void smartModeWithHistory() {
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("当前问题")
                .memoryConfig(new MemoryConfig("smart"))
                .includeHistory(true)
                .historyMessages(List.of(userMsg("历史1"), userMsg("历史2"))));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessages()).hasSize(3);
        assertThat(ctx.getInputMessages().get(0).getTextContent()).isEqualTo("历史1");
        assertThat(ctx.getInputMessages().get(2).getTextContent()).isEqualTo("当前问题");
    }

    @Test
    @DisplayName("contextData 注入：用户消息含页面上下文前缀")
    void contextDataInjected() {
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("帮我填表")
                .contextData(Map.of("pageType", "nutrition", "configSummary", Map.of("target", "减脂"))));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage().getTextContent())
                .startsWith("[当前页面上下文信息]")
                .contains("## 配置概要")
                .contains("target: 减脂")
                .endsWith("用户问题: 帮我填表");
    }

    @Test
    @DisplayName("contextData 为空：不注入，保留原文")
    void emptyContextDataNotInjected() {
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("原问题"));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage().getTextContent()).isEqualTo("原问题");
    }

    @Test
    @DisplayName("HITL 确认结果：写入输入消息 METADATA_CONFIRM_RESULTS")
    void hitlConfirmResultsInjected() {
        ConfirmResultRequest req = new ConfirmResultRequest();
        req.setToolCallId("call-1");
        req.setToolName("queryDb");
        req.setApproved(true);
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("继续")
                .confirmResults(List.of(req)));

        interceptor.preHandle(ctx);

        Object meta = ctx.getInputMessage().getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(meta).isNotNull().isInstanceOf(List.class);
        assertThat((List<?>) meta).hasSize(1);
    }

    @Test
    @DisplayName("HITL 确认结果缺少 toolCallId：跳过，不写入元数据")
    void hitlConfirmResultsMissingToolCallIdSkipped() {
        ConfirmResultRequest req = new ConfirmResultRequest();
        req.setApproved(true);
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("继续")
                .confirmResults(List.of(req)));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage().getMetadata()).doesNotContainKey(Msg.METADATA_CONFIRM_RESULTS);
    }
}
