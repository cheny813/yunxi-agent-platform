package io.yunxi.platform.execution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.interceptors.MemoryInterceptor;
import io.yunxi.platform.execution.spi.AgentEventAdapter;
import io.yunxi.platform.execution.spi.ExecutionInterceptor;
import io.yunxi.platform.execution.spi.ExecutionStrategy;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.ConfirmResultRequest;
import io.yunxi.platform.shared.entity.ConversationEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 执行引擎端到端测试。
 *
 * <p>不走 mock 拦截器链，而是构造<b>真实的</b> {@link DefaultInterceptorChain}（含真实的
 * {@link MemoryInterceptor}）与<b>真实的</b> {@link AgentExecutionEngine#execute} 调用路径，
 * 仅把"解析 Agent"与"调用 Agent"两处用 stub 替代（避免依赖真实 LLM / Redis / MySQL）。
 * 这样可验证：请求携带的 HITL 确认结果，能经由真实引擎 + 真实拦截器链，注入到最终发送给
 * Agent 的输入消息元数据中——即原 HITLConfirmInterceptor 逻辑下沉后，在真实调用路径上仍然成立。</p>
 */
@DisplayName("AgentExecutionEngine 端到端测试")
class AgentExecutionE2ETest {

    /** 真实 MemoryInterceptor（order=150，承担 HITL 确认注入）。 */
    private final MemoryInterceptor memoryInterceptor = new MemoryInterceptor();

    /**
     * 构造真实拦截器链：一个 stub 设置 resolvedAgent（绕过真实 Agent 解析），
     * 其余位置放真实 MemoryInterceptor，再加若干 no-op stub 还原生产链顺序骨架。
     */
    private DefaultInterceptorChain buildChain() {
        Agent mockAgent = mock(Agent.class);
        ExecutionInterceptor agentSetter = new ExecutionInterceptor() {
            @Override
            public int getOrder() {
                return 50;
            }

            @Override
            public void preHandle(ExecutionContext ctx) {
                ctx.setResolvedAgent(mockAgent);
            }
        };
        return new DefaultInterceptorChain(List.of(
                agentSetter,                       // 50：设置 resolvedAgent
                noop(100, "AuthResolve"),          // 100
                memoryInterceptor,                 // 150：真实记忆 + HITL 注入
                noop(200, "IntentPipeline"),       // 200
                noop(300, "RagRetrieval"),         // 300
                noop(400, "Audit")                 // 400
        ));
    }

    private static ExecutionInterceptor noop(int order, String name) {
        return new ExecutionInterceptor() {
            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public void preHandle(ExecutionContext ctx) {
                // no-op：仅占位，保持链顺序与生产一致
            }

            @Override
            public String toString() {
                return "Stub(" + name + ")";
            }
        };
    }

    /** stub 策略：捕获被注入后的输入消息，返回占位 Msg（不真正调用 Agent）。 */
    private static class CaptureStrategy implements ExecutionStrategy {
        final AtomicReference<Msg> captured = new AtomicReference<>();

        @Override
        public boolean supports(ExecutionContext ctx) {
            return !ctx.getRequest().isStreaming();
        }

        @Override
        public Object execute(ExecutionContext ctx) {
            captured.set(ctx.getInputMessage());
            return Msg.builder().textContent("OK").build();
        }
    }

    private AgentExecutionEngine buildEngine(DefaultInterceptorChain chain, CaptureStrategy strategy) {
        return new AgentExecutionEngine(
                chain,
                List.of(strategy),
                new EventOperatorChain(List.of()),
                mock(AgentEventAdapter.class),
                mock(AgentscopeCoreProperties.class));
    }

    private static ConfirmResultRequest approvedResult(String toolCallId, String toolName) {
        ConfirmResultRequest r = new ConfirmResultRequest();
        r.setToolCallId(toolCallId);
        r.setToolName(toolName);
        r.setApproved(true);
        return r;
    }

    @Test
    @DisplayName("端到端：请求携带 HITL 确认结果，经真实引擎+拦截器链注入输入消息元数据")
    void hitlConfirmResultsInjectedThroughRealEnginePath() {
        CaptureStrategy strategy = new CaptureStrategy();
        AgentExecutionEngine engine = buildEngine(buildChain(), strategy);

        ExecutionRequest request = ExecutionRequest.builder()
                .agentName("demo-agent")
                .conversationId("conv-1")
                .userId("user-1")
                .message("继续刚才的操作")
                .streaming(false)
                .includeHistory(false)
                .confirmResults(List.of(approvedResult("call-1", "queryDb")))
                .build();

        ExecutionResult result = engine.execute(request, (ConversationEntity) null);

        // 1) 引擎未报错（拦截器链与策略均正常走通）
        assertThat(result.isError())
                .as("携带确认结果的请求应正常执行，不应被引擎判为错误")
                .isFalse();

        // 2) stub 策略确实捕获到了发送给 Agent 的输入消息
        Msg input = strategy.captured.get();
        assertThat(input).as("策略应捕获到输入消息").isNotNull();

        // 3) 关键：HITL 确认结果被注入输入消息元数据 METADATA_CONFIRM_RESULTS
        Object meta = input.getMetadata() == null ? null : input.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(meta)
                .as("输入消息应携带 METADATA_CONFIRM_RESULTS 元数据")
                .isNotNull();
        assertThat(meta).isInstanceOf(List.class);
        assertThat((List<?>) meta).hasSize(1);
    }

    @Test
    @DisplayName("端到端：无 HITL 确认结果时，输入消息不含确认元数据")
    void noConfirmResultsYieldsNoMetadata() {
        CaptureStrategy strategy = new CaptureStrategy();
        AgentExecutionEngine engine = buildEngine(buildChain(), strategy);

        ExecutionRequest request = ExecutionRequest.builder()
                .agentName("demo-agent")
                .conversationId("conv-2")
                .userId("user-2")
                .message("普通提问")
                .streaming(false)
                .includeHistory(false)
                .build();

        ExecutionResult result = engine.execute(request, (ConversationEntity) null);

        assertThat(result.isError()).as("无确认结果的普通请求应正常执行").isFalse();

        Msg input = strategy.captured.get();
        assertThat(input).isNotNull();
        assertThat(input.getMetadata())
                .as("无确认结果时不应注入 METADATA_CONFIRM_RESULTS")
                .doesNotContainKey(Msg.METADATA_CONFIRM_RESULTS);
    }

    @Test
    @DisplayName("端到端：删除 AgentGateway 死代码后，该类已不在 classpath（统一入口收敛至 AgentExecutionEngine）")
    void agentGatewayRemovedFromClasspath() {
        assertThatThrownBy(() -> Class.forName("io.yunxi.platform.agent.gateway.AgentGateway"))
                .as("AgentGateway 应已被删除，加载应抛 ClassNotFoundException")
                .isInstanceOf(ClassNotFoundException.class);
    }
}
