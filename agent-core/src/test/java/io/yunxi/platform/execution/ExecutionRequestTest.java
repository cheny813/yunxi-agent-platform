package io.yunxi.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.shared.config.MemoryConfig;

/**
 * ExecutionRequest Builder 与默认值测试。
 */
@DisplayName("ExecutionRequest 归一化请求")
class ExecutionRequestTest {

    @Test
    @DisplayName("Builder 默认值：includeHistory=true，其余 boolean false / 引用 null")
    void builderDefaults() {
        ExecutionRequest req = ExecutionRequest.builder()
                .message("你好")
                .agentName("nutrition-agent")
                .build();

        assertThat(req.getMessage()).isEqualTo("你好");
        assertThat(req.getAgentName()).isEqualTo("nutrition-agent");
        assertThat(req.isIncludeHistory()).isTrue();
        assertThat(req.isStreaming()).isFalse();
        assertThat(req.isQuickMode()).isFalse();
        assertThat(req.isUseA2A()).isFalse();
        assertThat(req.isDeepMode()).isFalse();
        assertThat(req.isEnableThinking()).isFalse();
        assertThat(req.isStructuredOutput()).isFalse();
        assertThat(req.getContextData()).isNull();
        assertThat(req.getMemoryConfig()).isNull();
        assertThat(req.getProfile()).isNull();
        assertThat(req.getHistoryMessages()).isNull();
        assertThat(req.getConversationId()).isNull();
        assertThat(req.getUserId()).isNull();
    }

    @Test
    @DisplayName("Builder 链式赋值全部生效")
    void builderAllFields() {
        MemoryConfig memory = new MemoryConfig("smart");
        List<Msg> history = List.of(Msg.builder().textContent("历史").build());
        Map<String, Object> contextData = Map.of("pageType", "nutrition", "formData", Map.of("a", "b"));

        ExecutionRequest req = ExecutionRequest.builder()
                .message("继续")
                .contextData(contextData)
                .memoryConfig(memory)
                .includeHistory(false)
                .profile("p1")
                .useA2A(true)
                .deepMode(true)
                .enableThinking(true)
                .quickMode(true)
                .structuredOutput(true)
                .historyMessages(history)
                .agentName("a2")
                .conversationId("c1")
                .userId("u1")
                .streaming(true)
                .build();

        assertThat(req.getMessage()).isEqualTo("继续");
        assertThat(req.getContextData()).isSameAs(contextData);
        assertThat(req.getMemoryConfig()).isSameAs(memory);
        assertThat(req.isIncludeHistory()).isFalse();
        assertThat(req.getProfile()).isEqualTo("p1");
        assertThat(req.isUseA2A()).isTrue();
        assertThat(req.isDeepMode()).isTrue();
        assertThat(req.isEnableThinking()).isTrue();
        assertThat(req.isQuickMode()).isTrue();
        assertThat(req.isStructuredOutput()).isTrue();
        assertThat(req.getHistoryMessages()).isSameAs(history);
        assertThat(req.getAgentName()).isEqualTo("a2");
        assertThat(req.getConversationId()).isEqualTo("c1");
        assertThat(req.getUserId()).isEqualTo("u1");
        assertThat(req.isStreaming()).isTrue();
    }
}
