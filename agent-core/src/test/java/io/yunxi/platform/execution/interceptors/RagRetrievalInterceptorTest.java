package io.yunxi.platform.execution.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.file.FileType;
import io.yunxi.platform.file.FileUploadService;
import io.yunxi.platform.file.dto.FileSearchResult;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties.InterceptorProperties;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;

/**
 * RagRetrievalInterceptor（order=300）：开关、触发条件、注入与异常隔离测试。
 */
@DisplayName("RagRetrievalInterceptor 文件检索注入")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagRetrievalInterceptorTest {

    @Mock
    private FileUploadService fileUploadService;
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
        when(conversation.getUserId()).thenReturn("u1");
        interceptor = new RagRetrievalInterceptor(fileUploadService, properties);
        ctx = newContext("smart", true);
    }

    private ExecutionContext newContext(String memoryMode, boolean includeHistory) {
        ExecutionRequest request = ExecutionRequest.builder()
                .message("检索问题")
                .agentName("agent-a")
                .conversationId("conv-1")
                .memoryConfig(new MemoryConfig(memoryMode))
                .includeHistory(includeHistory)
                .build();
        ExecutionContext c = new ExecutionContext(request, "conv-1", "u1", "agent-a");
        c.setConversation(conversation);
        c.setInputMessages(new java.util.ArrayList<>(List.of(Msg.builder().textContent("原始问题").build())));
        return c;
    }

    private FileSearchResult docResult() {
        return FileSearchResult.builder()
                .fileName("营养指南.pdf")
                .similarity(0.85)
                .fileType(FileType.DOCUMENT)
                .content("蛋白质每天摄入量建议 60g")
                .build();
    }

    @Test
    @DisplayName("开关关闭：不检索不注入")
    void disabledSkipsRetrieval() {
        when(interceptorProperties.isRagEnabled()).thenReturn(false);

        interceptor.preHandle(ctx);

        verify(fileUploadService, never()).searchRelevantFiles(any());
        assertThat(ctx.getInputMessages()).hasSize(1);
    }

    @Test
    @DisplayName("inputMessages 为空：跳过")
    void emptyMessagesSkips() {
        ctx.setInputMessages(new java.util.ArrayList<>());

        interceptor.preHandle(ctx);

        verify(fileUploadService, never()).searchRelevantFiles(any());
    }

    @Test
    @DisplayName("非会话型（conversation=null）：跳过")
    void nonConversationSkips() {
        ctx.setConversation(null);

        interceptor.preHandle(ctx);

        verify(fileUploadService, never()).searchRelevantFiles(any());
    }

    @Test
    @DisplayName("无记忆模式：跳过")
    void noneMemorySkips() {
        ctx = newContext("none", true);

        interceptor.preHandle(ctx);

        verify(fileUploadService, never()).searchRelevantFiles(any());
    }

    @Test
    @DisplayName("includeHistory=false：跳过")
    void noHistorySkips() {
        ctx = newContext("smart", false);

        interceptor.preHandle(ctx);

        verify(fileUploadService, never()).searchRelevantFiles(any());
    }

    @Test
    @DisplayName("检索到文件：RAG 上下文注入到消息首条")
    void retrievalInjectsContext() {
        when(fileUploadService.searchRelevantFiles(any())).thenReturn(List.of(docResult()));

        interceptor.preHandle(ctx);

        // 校验检索参数
        verify(fileUploadService).searchRelevantFiles(argThat(req ->
                req.getUserId().equals("u1")
                        && req.getQuery().equals("检索问题")
                && req.getTopK() == 3
                && req.getThreshold() == 0.7
                && Boolean.TRUE.equals(req.getIncludeContent())));
        // 注入后首条为 RAG 上下文
        assertThat(ctx.getInputMessages()).hasSize(2);
        assertThat(ctx.getInputMessages().get(0).getTextContent())
                .startsWith("[相关文件上下文]")
                .contains("营养指南.pdf")
                .contains("蛋白质每天摄入量建议 60g");
        assertThat(ctx.getInputMessages().get(1).getTextContent()).isEqualTo("原始问题");
    }

    @Test
    @DisplayName("未检索到文件：不注入")
    void noResultsNoInjection() {
        when(fileUploadService.searchRelevantFiles(any())).thenReturn(List.of());

        interceptor.preHandle(ctx);

        assertThat(ctx.getInputMessages()).hasSize(1);
    }

    @Test
    @DisplayName("检索异常被忽略，原消息不变")
    void retrievalExceptionIgnored() {
        when(fileUploadService.searchRelevantFiles(any())).thenThrow(new RuntimeException("检索服务不可用"));

        interceptor.preHandle(ctx); // 不抛出

        assertThat(ctx.getInputMessages()).hasSize(1);
        assertThat(ctx.getInputMessages().get(0).getTextContent()).isEqualTo("原始问题");
    }
}
