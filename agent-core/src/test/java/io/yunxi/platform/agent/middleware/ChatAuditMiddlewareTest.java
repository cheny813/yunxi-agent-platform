package io.yunxi.platform.agent.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.yunxi.platform.shared.entity.ChatLogEntity;
import io.yunxi.platform.shared.mapper.ConversationMapper;
import reactor.core.publisher.Flux;

/**
 * ChatAuditMiddleware：开关、落库内容与异常隔离测试。
 */
@DisplayName("对话审计中间件")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatAuditMiddlewareTest {

    @Mock
    private ConversationMapper conversationMapper;

    private ChatAuditMiddleware middleware;
    private Agent agent;
    private RuntimeContext enabledCtx;

    @BeforeEach
    void setUp() {
        middleware = new ChatAuditMiddleware(conversationMapper, "agent-a");
        agent = null;
        enabledCtx = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("conv-1")
                .build();
        enabledCtx.put(CallContextKeys.AUDIT_ENABLED_KEY, Boolean.TRUE);
    }

    private static AgentInput inputOf(String text) {
        return new AgentInput(List.of(Msg.builder().textContent(text).build()));
    }

    private static Function<AgentInput, Flux<AgentEvent>> okChain() {
        return in -> Flux.empty();
    }

    @Test
    @DisplayName("审计关闭：不落库")
    void disabledNoAudit() {
        RuntimeContext off = RuntimeContext.builder().userId("user-1").sessionId("conv-1").build();

        middleware.onAgent(agent, off, inputOf("审计问题"), okChain()).blockLast();

        verify(conversationMapper, never()).insertChatLog(any());
    }

    @Test
    @DisplayName("审计开启：成功请求落库 success=true 且带用户消息")
    void enabledSuccessPersists() {
        middleware.onAgent(agent, enabledCtx, inputOf("审计问题"), okChain()).blockLast();

        ArgumentCaptor<ChatLogEntity> captor = ArgumentCaptor.forClass(ChatLogEntity.class);
        verify(conversationMapper).insertChatLog(captor.capture());
        ChatLogEntity entity = captor.getValue();
        assertThat(entity.getConversationId()).isEqualTo("conv-1");
        assertThat(entity.getAgentName()).isEqualTo("agent-a");
        assertThat(entity.getUserId()).isEqualTo("user-1");
        assertThat(entity.getUserMessage()).isEqualTo("审计问题");
        assertThat(entity.getSuccess()).isTrue();
        assertThat(entity.getErrorMessage()).isNull();
        assertThat(entity.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("审计开启：失败请求落库 success=false 且带错误消息")
    void enabledFailurePersists() {
        Function<AgentInput, Flux<AgentEvent>> failing =
                in -> Flux.error(new IllegalStateException("模型调用超时"));

        assertThatCode(() -> middleware.onAgent(agent, enabledCtx, inputOf("审计问题"), failing)
                .onErrorResume(e -> Flux.empty())
                .blockLast()).doesNotThrowAnyException();

        ArgumentCaptor<ChatLogEntity> captor = ArgumentCaptor.forClass(ChatLogEntity.class);
        verify(conversationMapper).insertChatLog(captor.capture());
        assertThat(captor.getValue().getSuccess()).isFalse();
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("模型调用超时");
    }

    @Test
    @DisplayName("落库异常被隔离，不向主流程抛出")
    void persistenceFailureIsolated() {
        when(conversationMapper.insertChatLog(any())).thenThrow(new RuntimeException("DB 不可用"));

        assertThatCode(() -> middleware.onAgent(agent, enabledCtx, inputOf("审计问题"), okChain())
                .blockLast()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("超长错误消息截断至 500 字符")
    void longErrorMessageTruncated() {
        String longError = "E".repeat(1200);
        Function<AgentInput, Flux<AgentEvent>> failing =
                in -> Flux.error(new IllegalStateException(longError));

        middleware.onAgent(agent, enabledCtx, inputOf("审计问题"), failing)
                .onErrorResume(e -> Flux.empty())
                .blockLast();

        ArgumentCaptor<ChatLogEntity> captor = ArgumentCaptor.forClass(ChatLogEntity.class);
        verify(conversationMapper).insertChatLog(captor.capture());
        assertThat(captor.getValue().getErrorMessage()).hasSize(500);
    }
}
