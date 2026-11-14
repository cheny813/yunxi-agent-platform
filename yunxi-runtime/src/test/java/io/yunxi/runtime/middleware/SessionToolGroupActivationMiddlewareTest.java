package io.yunxi.runtime.middleware;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/**
 * 针对 {@link SessionToolGroupActivationMiddleware} 的聚焦单测：锁定会话级工具组并集回写语义，
 * 以及「不触碰共享 toolkit、跨会话互不干扰、下游 Flux 透传」三项不变量。
 *
 * <p>工具解析路径由底层框架读 {@code AgentState.toolContext}（见 ReActAgent#activateSlotForContext），
 * 本中间件只改调用级会话状态，故不同会话各自解析自身 toolContext，天然并发安全。
 */
@ExtendWith(MockitoExtension.class)
class SessionToolGroupActivationMiddlewareTest {

    private static final String AGENT = "my-agent";

    private static Agent agent() {
        Agent a = mock(Agent.class);
        when(a.getName()).thenReturn(AGENT);
        return a;
    }

    private static AgentState stateWith(ToolContextState toolContext) {
        return AgentState.builder()
                .sessionId("sess-1")
                .userId("user-1")
                .toolContext(toolContext)
                .build();
    }

    private static RuntimeContext ctxWith(AgentState state) {
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.setAgentState(state);
        return ctx;
    }

    @Test
    void onAgent_unionsRegisteredGroupsIntoSessionToolContext() {
        SessionToolGroupRegistry registry = new SessionToolGroupRegistry();
        registry.register(AGENT, List.of("mcp-a", "mcp-b"));

        ToolContextState tc = ToolContextState.builder().addActivatedGroup("builtin").build();
        AgentState state = stateWith(tc);

        SessionToolGroupActivationMiddleware mw = new SessionToolGroupActivationMiddleware(registry);
        Flux<AgentEvent> downstream = Flux.just(mock(AgentEvent.class));
        Function<AgentInput, Flux<AgentEvent>> next = input -> downstream;

        Flux<AgentEvent> result = mw.onAgent(agent(), ctxWith(state), mock(AgentInput.class), next);

        assertSame(downstream, result, "中间件须透传下游 Flux，不做额外订阅/包装");
        assertTrue(tc.getActivatedGroups().containsAll(List.of("builtin", "mcp-a", "mcp-b")),
                "会话已激活组应为预置组与登记组的并集");
        assertEquals(3, tc.getActivatedGroups().size(), "并集不应删除既有组");
    }

    @Test
    void onAgent_noRegisteredGroups_isNoOp() {
        SessionToolGroupRegistry registry = new SessionToolGroupRegistry();
        ToolContextState tc = ToolContextState.builder().addActivatedGroup("builtin").build();
        AgentState state = stateWith(tc);

        SessionToolGroupActivationMiddleware mw = new SessionToolGroupActivationMiddleware(registry);
        mw.onAgent(agent(), ctxWith(state), mock(AgentInput.class), input -> Flux.empty());

        assertEquals(List.of("builtin"), tc.getActivatedGroups(),
                "未登记工具组时不应改动会话激活组");
    }

    @Test
    void onAgent_otherAgentRegistered_isNoOp() {
        SessionToolGroupRegistry registry = new SessionToolGroupRegistry();
        registry.register("other-agent", List.of("mcp-x"));
        ToolContextState tc = ToolContextState.builder().addActivatedGroup("builtin").build();
        AgentState state = stateWith(tc);

        SessionToolGroupActivationMiddleware mw = new SessionToolGroupActivationMiddleware(registry);
        mw.onAgent(agent(), ctxWith(state), mock(AgentInput.class), input -> Flux.empty());

        assertEquals(List.of("builtin"), tc.getActivatedGroups(),
                "其他 Agent 登记的工具组不应影响本会话");
    }

    @Test
    void onAgent_invokesDownstreamExactlyOnce() {
        SessionToolGroupRegistry registry = new SessionToolGroupRegistry();
        registry.register(AGENT, List.of("mcp-a"));
        AgentState state = stateWith(ToolContextState.builder().build());

        SessionToolGroupActivationMiddleware mw = new SessionToolGroupActivationMiddleware(registry);
        AgentInput input = mock(AgentInput.class);
        Function<AgentInput, Flux<AgentEvent>> next = mock(Function.class);
        when(next.apply(input)).thenReturn(Flux.empty());

        mw.onAgent(agent(), ctxWith(state), input, next);

        verify(next, times(1)).apply(input);
    }
}
