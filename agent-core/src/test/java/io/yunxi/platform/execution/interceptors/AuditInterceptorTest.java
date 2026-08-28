package io.yunxi.platform.execution.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties.AuditProperties;
import io.yunxi.platform.shared.entity.ChatLogEntity;
import io.yunxi.platform.shared.mapper.ConversationMapper;

/**
 * AuditInterceptor（order=500）：开关、落库与异常隔离测试。
 */
@DisplayName("AuditInterceptor 通用审计")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditInterceptorTest {

    @Mock
    private AgentscopeCoreProperties properties;
    @Mock
    private AuditProperties auditProperties;
    @Mock
    private ConversationMapper conversationMapper;

    private AuditInterceptor interceptor;
    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        when(properties.getAudit()).thenReturn(auditProperties);
        interceptor = new AuditInterceptor(properties, conversationMapper);
        ExecutionRequest request = ExecutionRequest.builder()
                .message("审计问题")
                .agentName("agent-a")
                .conversationId("conv-1")
                .userId("user-1")
                .build();
        ctx = new ExecutionContext(request, "conv-1", "user-1", "agent-a");
    }

    @Test
    @DisplayName("审计关闭：pre 不记起始点，post 不落库")
    void disabledNoAudit() {
        when(auditProperties.isEnabled()).thenReturn(false);

        interceptor.preHandle(ctx);
        interceptor.postHandle(ctx);

        verify(conversationMapper, never()).insertChatLog(any());
        assertThat(ctx.<Object>getAttribute(AuditInterceptor.ATTR_START_TIME)).isNull();
    }

    @Test
    @DisplayName("审计开启：成功请求落库 success=true")
    void enabledSuccessPersists() {
        when(auditProperties.isEnabled()).thenReturn(true);

        interceptor.preHandle(ctx);
        assertThat(ctx.<String>getAttribute(AuditInterceptor.ATTR_REQUEST_ID)).isNotNull();
        assertThat(ctx.<Object>getAttribute(AuditInterceptor.ATTR_START_TIME)).isInstanceOf(Long.class);

        ctx.setExecutionError(null);
        interceptor.postHandle(ctx);

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
        when(auditProperties.isEnabled()).thenReturn(true);

        interceptor.preHandle(ctx);
        ctx.setExecutionError("模型调用超时");
        interceptor.postHandle(ctx);

        ArgumentCaptor<ChatLogEntity> captor = ArgumentCaptor.forClass(ChatLogEntity.class);
        verify(conversationMapper).insertChatLog(captor.capture());
        assertThat(captor.getValue().getSuccess()).isFalse();
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("模型调用超时");
    }

    @Test
    @DisplayName("pre 未执行（链中断）：post 跳过落库")
    void postWithoutPreSkips() {
        when(auditProperties.isEnabled()).thenReturn(true);

        interceptor.postHandle(ctx);

        verify(conversationMapper, never()).insertChatLog(any());
    }

    @Test
    @DisplayName("落库异常被隔离，不向主流程抛出")
    void persistenceFailureIsolated() {
        when(auditProperties.isEnabled()).thenReturn(true);
        when(conversationMapper.insertChatLog(any())).thenThrow(new RuntimeException("DB 不可用"));

        interceptor.preHandle(ctx);
        interceptor.postHandle(ctx); // 不抛出
    }

    @Test
    @DisplayName("超长错误消息截断至 500 字符")
    void longErrorMessageTruncated() {
        when(auditProperties.isEnabled()).thenReturn(true);
        String longError = "E".repeat(1200);

        interceptor.preHandle(ctx);
        ctx.setExecutionError(longError);
        interceptor.postHandle(ctx);

        ArgumentCaptor<ChatLogEntity> captor = ArgumentCaptor.forClass(ChatLogEntity.class);
        verify(conversationMapper).insertChatLog(captor.capture());
        assertThat(captor.getValue().getErrorMessage()).hasSize(500);
    }
}
