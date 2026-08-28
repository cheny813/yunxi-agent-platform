package io.yunxi.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ExecutionContext 共享状态存取测试。
 */
@DisplayName("ExecutionContext 执行上下文")
class ExecutionContextTest {

    private ExecutionContext newContext() {
        ExecutionRequest request = ExecutionRequest.builder()
                .message("你好")
                .agentName("agent-a")
                .conversationId("conv-1")
                .userId("user-1")
                .build();
        return new ExecutionContext(request, request.getConversationId(), request.getUserId(), request.getAgentName());
    }

    @Test
    @DisplayName("构造参数与不可变字段")
    void constructorFields() {
        ExecutionContext ctx = newContext();

        assertThat(ctx.getRequest()).isNotNull();
        assertThat(ctx.getRequest().getMessage()).isEqualTo("你好");
        assertThat(ctx.getConversationId()).isEqualTo("conv-1");
        assertThat(ctx.getUserId()).isEqualTo("user-1");
        assertThat(ctx.getAgentName()).isEqualTo("agent-a");
        assertThat(ctx.getExecutionError()).isNull();
        assertThat(ctx.getAttributes()).isEmpty();
    }

    @Test
    @DisplayName("setUserId 回写（AuthResolve 兜底）")
    void setUserId() {
        ExecutionContext ctx = newContext();
        ctx.setUserId("user-2");
        assertThat(ctx.getUserId()).isEqualTo("user-2");
    }

    @Test
    @DisplayName("attributes 泛型读取")
    void attributesTypedAccess() {
        ExecutionContext ctx = newContext();
        ctx.setAttribute("audit.requestId", "req-123");
        ctx.setAttribute("thinkingText", "思考中");

        String requestId = ctx.getAttribute("audit.requestId");
        assertThat(requestId).isEqualTo("req-123");
        assertThat(ctx.<String>getAttribute("thinkingText")).isEqualTo("思考中");
        assertThat(ctx.<String>getAttribute("不存在")).isNull();
        assertThat(ctx.getAttributes()).containsEntry("audit.requestId", "req-123");
    }

    @Test
    @DisplayName("执行错误标记读写")
    void executionError() {
        ExecutionContext ctx = newContext();
        assertThat(ctx.getExecutionError()).isNull();
        ctx.setExecutionError("模拟失败");
        assertThat(ctx.getExecutionError()).isEqualTo("模拟失败");
    }
}
