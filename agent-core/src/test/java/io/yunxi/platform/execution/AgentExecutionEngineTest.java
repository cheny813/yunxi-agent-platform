package io.yunxi.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.spi.AgentEventAdapter;
import io.yunxi.platform.execution.spi.ExecutionStrategy;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * AgentExecutionEngine 统一执行引擎核心路径测试。
 */
@DisplayName("AgentExecutionEngine 统一执行引擎")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentExecutionEngineTest {

    @Mock
    private DefaultInterceptorChain interceptorChain;
    @Mock
    private EventOperatorChain operatorChain;
    @Mock
    private AgentEventAdapter eventAdapter;
    @Mock
    private AgentscopeCoreProperties properties;
    @Mock
    private ExecutionStrategy strategy;

    private AgentExecutionEngine engine;
    private ExecutionRequest request;
    private Agent resolvedAgent;

    @BeforeEach
    void setUp() {
        when(properties.getChatTimeoutSeconds()).thenReturn(30);
        // 协议生命周期钩子（引擎不再依赖具体协议构建器，start/error 由适配器产出）
        when(eventAdapter.onStart(any())).thenReturn(List.of("start"));
        when(eventAdapter.onError(any(), any())).thenReturn("error");
        engine = new AgentExecutionEngine(
                interceptorChain, List.of(strategy), operatorChain, eventAdapter, properties);
        request = ExecutionRequest.builder()
                .message("你好")
                .agentName("agent-a")
                .conversationId("conv-1")
                .userId("user-1")
                .build();
        resolvedAgent = mock(Agent.class);
    }

    /** 让拦截器链正常通过并注入已解析 Agent */
    private void chainResolvesAgent() {
        doAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(0);
            ctx.setResolvedAgent(resolvedAgent);
            return null;
        }).when(interceptorChain).preHandleAll(any());
    }

    @Test
    @DisplayName("拦截器链异常：返回 error 并触发 postHandle 收尾")
    void interceptorFailureReturnsError() {
        doThrow(new RuntimeException("拦截器失败")).when(interceptorChain).preHandleAll(any());

        ExecutionResult result = engine.execute(request, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage()).contains("拦截器失败");
        assertThat(result.isStreaming()).isFalse();
        verify(interceptorChain).postHandleAll(any());
    }

    @Test
    @DisplayName("Agent 未解析：返回 error")
    void unresolvedAgentReturnsError() {
        // 链正常通过但不设置 resolvedAgent
        ExecutionResult result = engine.execute(request, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage()).contains("Agent 未解析");
        verify(interceptorChain).postHandleAll(any());
    }

    @Test
    @DisplayName("无匹配策略：返回 error")
    void noMatchingStrategyReturnsError() {
        chainResolvesAgent();
        when(strategy.supports(any())).thenReturn(false);
        when(properties.getChatTimeoutSeconds()).thenReturn(30);

        ExecutionResult result = engine.execute(request, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage()).isEqualTo("无匹配执行策略");
        verify(interceptorChain).postHandleAll(any());
    }

    @Test
    @DisplayName("阻塞通道成功：返回 blocking 结果并同步收尾")
    void blockingSuccess() {
        chainResolvesAgent();
        when(strategy.supports(any())).thenReturn(true);
        Msg answer = Msg.builder().textContent("答案").build();
        when(strategy.execute(any())).thenReturn(answer);

        ExecutionResult result = engine.execute(request, null);

        assertThat(result.isError()).isFalse();
        assertThat(result.isStreaming()).isFalse();
        assertThat(result.getResult().getTextContent()).isEqualTo("答案");
        verify(interceptorChain).postHandleAll(any());
    }

    @Test
    @DisplayName("阻塞通道响应为空：返回 error")
    void blockingNullResponseReturnsError() {
        chainResolvesAgent();
        when(strategy.supports(any())).thenReturn(true);
        when(strategy.execute(any())).thenReturn(null);

        ExecutionResult result = engine.execute(request, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage()).isEqualTo("Agent 响应为空");
        verify(interceptorChain).postHandleAll(any());
    }

    @Test
    @DisplayName("流式通道成功：订阅后 doFinally 触发收尾")
    void streamingSuccess() {
        request = ExecutionRequest.builder()
                .message("你好")
                .agentName("agent-a")
                .conversationId("conv-1")
                .userId("user-1")
                .streaming(true)
                .build();
        chainResolvesAgent();
        when(strategy.supports(any())).thenReturn(true);
        AgentEvent event = mock(AgentEvent.class);
        Flux<AgentEvent> events = Flux.just(event);
        when(strategy.execute(any())).thenReturn(events);
        when(operatorChain.apply(any(), any())).thenReturn(events);
        when(eventAdapter.convert(any(), any())).thenReturn(List.of("内容片段"));

        ExecutionResult result = engine.execute(request, null);

        assertThat(result.isStreaming()).isTrue();
        assertThat(result.getStream()).isNotNull();
        assertThat(result.isError()).isFalse();

        StepVerifier.create(result.getStream())
                .expectNextCount(2) // start + content
                .verifyComplete();

        // 流完成触发 doFinally 收尾
        verify(interceptorChain).postHandleAll(any());
        assertThat(result.getContext().getExecutionError()).isNull();
    }

    @Test
    @DisplayName("流式通道被取消：补记执行错误并收尾")
    void streamingCancelled() {
        request = ExecutionRequest.builder()
                .message("你好")
                .agentName("agent-a")
                .conversationId("conv-1")
                .userId("user-1")
                .streaming(true)
                .build();
        chainResolvesAgent();
        when(strategy.supports(any())).thenReturn(true);
        AgentEvent event = mock(AgentEvent.class);
        Flux<AgentEvent> events = Flux.just(event);
        when(strategy.execute(any())).thenReturn(events);
        when(operatorChain.apply(any(), any())).thenReturn(events);
        when(eventAdapter.convert(any(), any())).thenReturn(List.of("内容片段"));

        ExecutionResult result = engine.execute(request, null);

        StepVerifier.create(result.getStream())
                .thenCancel()
                .verify();

        assertThat(result.getContext().getExecutionError()).isEqualTo("客户端取消");
        verify(interceptorChain).postHandleAll(any());
    }

    @Test
    @DisplayName("流式通道异常：兜底为错误消息并记录执行错误")
    void streamingError() {
        request = ExecutionRequest.builder()
                .message("你好")
                .agentName("agent-a")
                .conversationId("conv-1")
                .userId("user-1")
                .streaming(true)
                .build();
        chainResolvesAgent();
        when(strategy.supports(any())).thenReturn(true);
        Flux<AgentEvent> events = Flux.error(new RuntimeException("模型调用失败"));
        when(strategy.execute(any())).thenReturn(events);
        when(operatorChain.apply(any(), any())).thenReturn(events);

        ExecutionResult result = engine.execute(request, null);

        assertThat(result.isStreaming()).isTrue();
        StepVerifier.create(result.getStream())
                .expectNextCount(2) // start + 错误消息兜底
                .verifyComplete();

        assertThat(result.getContext().getExecutionError()).contains("模型调用失败");
        verify(interceptorChain).postHandleAll(any());
    }
}
