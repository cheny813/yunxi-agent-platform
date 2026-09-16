package io.yunxi.platform.execution.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties.InterceptorProperties;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;

/**
 * RagRetrievalInterceptor（order=300）：检索启用条件测试。
 *
 * <p>检索动作本身由请求级中间件执行，本拦截器只负责判定是否启用并写入检索词。</p>
 */
@DisplayName("RagRetrievalInterceptor 文件检索启用条件")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagRetrievalInterceptorTest {

    @Mock
    private AgentscopeCoreProperties properties;
    @Mock
    private InterceptorProperties interceptorProperties;
    @Mock
    private ConversationEntity conversation;

    private RagRetrievalInterceptor interceptor;
    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        when(properties.getInterceptor()).thenReturn(interceptorProperties);
        when(interceptorProperties.isRagEnabled()).thenReturn(true);
        interceptor = new RagRetrievalInterceptor(properties);
        ctx = newContext("smart", true, true);
    }

    private ExecutionContext newContext(String memoryMode, boolean includeHistory, boolean withConversation) {
        ExecutionRequest request = ExecutionRequest.builder()
                .message("检索问题")
                .agentName("agent-a")
                .conversationId("conv-1")
                .memoryConfig(new MemoryConfig(memoryMode))
                .includeHistory(includeHistory)
                .build();
        ExecutionContext c = new ExecutionContext(request, "conv-1", "u1", "agent-a");
        if (withConversation) {
            c.setConversation(conversation);
        }
        return c;
    }

    @Test
    @DisplayName("开关关闭：不写入检索词")
    void disabledSkips() {
        when(interceptorProperties.isRagEnabled()).thenReturn(false);

        interceptor.preHandle(ctx);

        assertThat(ctx.getAttributes()).doesNotContainKey(RagRetrievalInterceptor.ATTR_RAG_QUERY);
    }

    @Test
    @DisplayName("非会话型：不写入检索词")
    void nonConversationSkips() {
        ctx = newContext("smart", true, false);

        interceptor.preHandle(ctx);

        assertThat(ctx.getAttributes()).doesNotContainKey(RagRetrievalInterceptor.ATTR_RAG_QUERY);
    }

    @Test
    @DisplayName("无记忆模式：不写入检索词")
    void noneMemorySkips() {
        ctx = newContext("none", true, true);

        interceptor.preHandle(ctx);

        assertThat(ctx.getAttributes()).doesNotContainKey(RagRetrievalInterceptor.ATTR_RAG_QUERY);
    }

    @Test
    @DisplayName("includeHistory=false：不写入检索词")
    void noHistorySkips() {
        ctx = newContext("smart", false, true);

        interceptor.preHandle(ctx);

        assertThat(ctx.getAttributes()).doesNotContainKey(RagRetrievalInterceptor.ATTR_RAG_QUERY);
    }

    @Test
    @DisplayName("条件满足：写入检索词")
    void applicableWritesQuery() {
        interceptor.preHandle(ctx);

        assertThat(ctx.getAttributes())
                .containsEntry(RagRetrievalInterceptor.ATTR_RAG_QUERY, "检索问题");
    }
}
