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
    @DisplayName("携带历史：本拦截器只产出当前用户消息，历史由请求级中间件注入")
    void historyInjectionMovedToMiddleware() {
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("当前问题")
                .memoryConfig(new MemoryConfig("smart"))
                .includeHistory(true)
                .historyMessages(List.of(userMsg("历史1"), userMsg("历史2"))));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessages()).hasSize(1);
        assertThat(ctx.getInputMessages().get(0).getTextContent()).isEqualTo("当前问题");
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

    @Test
    @DisplayName("HITL 确认轮：清空用户文本，仅保留确认结果元数据")
    void hitlConfirmTurnStripsUserText() {
        ConfirmResultRequest req = new ConfirmResultRequest();
        req.setToolCallId("call-1");
        req.setToolName("queryDb");
        req.setApproved(true);
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("那顺便再问你三个问题：……")
                .confirmResults(List.of(req)));

        interceptor.preHandle(ctx);

        // 框架在确认轮直接 resumeAgent()，不补一次推理；用户文本若留下会成为永不回答的悬空问题
        assertThat(ctx.getInputMessage().getTextContent())
                .as("确认轮不应把用户新问题带进上下文")
                .isNullOrEmpty();
        assertThat(ctx.getInputMessage().getMetadata())
                .as("确认结果元数据必须保留")
                .containsKey(Msg.METADATA_CONFIRM_RESULTS);
    }

    @Test
    @DisplayName("HITL 确认轮：contextData 一并被剥离，不得随确认轮进入上下文")
    void hitlConfirmTurnStripsContextDataToo() {
        ConfirmResultRequest req = new ConfirmResultRequest();
        req.setToolCallId("call-1");
        req.setApproved(false);
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("随便")
                .contextData(Map.of("pageType", "nutrition"))
                .confirmResults(List.of(req)));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage().getTextContent()).isNullOrEmpty();
    }

    @Test
    @DisplayName("非确认轮：用户文本原样保留（不能被裁剪逻辑误伤）")
    void normalTurnKeepsUserText() {
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("正常提问")
                .confirmResults(List.of()));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage().getTextContent()).isEqualTo("正常提问");
    }

    @Test
    @DisplayName("确认项全部缺少 toolCallId：视为非确认轮，用户文本保留")
    void confirmResultsWithoutValidIdKeepsUserText() {
        ConfirmResultRequest invalid = new ConfirmResultRequest();
        invalid.setApproved(true);
        ExecutionContext ctx = ctxWith(ExecutionRequest.builder()
                .message("仍应保留")
                .confirmResults(List.of(invalid)));

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessage().getTextContent())
                .as("无有效 toolCallId 时不构成确认轮，不应裁剪文本")
                .isEqualTo("仍应保留");
    }
}
