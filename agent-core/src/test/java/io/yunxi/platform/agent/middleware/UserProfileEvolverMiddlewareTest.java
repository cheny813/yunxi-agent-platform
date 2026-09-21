package io.yunxi.platform.agent.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
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
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.yunxi.platform.profile.ProfileEvolutionThrottle;
import io.yunxi.platform.profile.RedisUserProfileStore;
import io.yunxi.platform.spi.UserProfileEvolver;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;
import reactor.core.publisher.Flux;

/**
 * UserProfileEvolverMiddleware：收尾异步触发、节流跳过、事件流隔离与对话组装测试。
 */
@DisplayName("画像演进中间件")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserProfileEvolverMiddlewareTest {

    @Mock
    private RedisUserProfileStore store;
    @Mock
    private UserProfileEvolver evolver;
    @Mock
    private ProfileEvolutionThrottle throttle;

    private UserProfileEvolverMiddleware middleware;
    private Agent agent;

    @BeforeEach
    void setUp() {
        middleware = new UserProfileEvolverMiddleware(store, evolver, throttle);
        agent = null;
        when(throttle.tryAcquire(any())).thenReturn(true);
        when(store.getProfile(any())).thenReturn(
                new UserProfile("u1", List.of(), Map.of(), Map.of(), 1L));
        UserProfile evolved = new UserProfile("u1", List.of(), Map.of(), Map.of(), 2L);
        when(evolver.evolve(any(), anyList())).thenReturn(evolved);
    }

    private static RuntimeContext ctxOf(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId("conv-1").build();
    }

    private static AgentInput inputOf(String text) {
        return new AgentInput(List.of(Msg.builder().role(MsgRole.USER).textContent(text).build()));
    }

    /** 事件流返回一条 AgentResultEvent，模拟助手最终答复。 */
    private static Function<AgentInput, Flux<AgentEvent>> okChainWithResult(String assistantText) {
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent(assistantText).build();
        return in -> Flux.just(new AgentResultEvent(result));
    }

    @Test
    @DisplayName("收尾触发抽取并落库，抽取输入含用户与助手两条消息")
    void triggersEvolveWithUserAndAssistant() {
        middleware.onAgent(agent, ctxOf("u1"), inputOf("我是小明"),
                okChainWithResult("你好小明")).blockLast();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(evolver, timeout(3000)).evolve(any(), captor.capture());
        List<Msg> turns = captor.getValue();
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).getRole()).isEqualTo(MsgRole.USER);
        assertThat(turns.get(0).getTextContent()).isEqualTo("我是小明");
        assertThat(turns.get(1).getRole()).isEqualTo(MsgRole.ASSISTANT);
        assertThat(turns.get(1).getTextContent()).isEqualTo("你好小明");

        verify(store, timeout(3000)).saveProfile(eq("u1"), any());
    }

    @Test
    @DisplayName("节流不放行：不触发抽取")
    void throttledSkipsEvolve() {
        when(throttle.tryAcquire("u1")).thenReturn(false);

        middleware.onAgent(agent, ctxOf("u1"), inputOf("我是小明"),
                okChainWithResult("你好")).blockLast();

        verify(evolver, never()).evolve(any(), anyList());
        verify(store, never()).saveProfile(any(), any());
    }

    @Test
    @DisplayName("userId 为空：不触发抽取且原样透传事件流")
    void blankUserSkips() {
        middleware.onAgent(agent, RuntimeContext.builder().build(), inputOf("我是小明"),
                okChainWithResult("你好")).blockLast();

        verify(throttle, never()).tryAcquire(any());
        verify(evolver, never()).evolve(any(), anyList());
    }

    @Test
    @DisplayName("输入无用户消息：不触发抽取")
    void noUserTextSkips() {
        AgentInput noUser = new AgentInput(List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent("sys").build()));

        middleware.onAgent(agent, ctxOf("u1"), noUser, okChainWithResult("你好")).blockLast();

        verify(evolver, never()).evolve(any(), anyList());
    }

    @Test
    @DisplayName("抽取抛异常：被隔离，不影响主事件流")
    void evolverFailureIsolated() {
        when(evolver.evolve(any(), anyList())).thenThrow(new RuntimeException("抽取失败"));

        assertThatCode(() -> middleware.onAgent(agent, ctxOf("u1"), inputOf("我是小明"),
                okChainWithResult("你好")).blockLast()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("助手无结果事件：仍以单条用户消息触发抽取")
    void userOnlyWhenNoAssistantResult() {
        Function<AgentInput, Flux<AgentEvent>> emptyChain = in -> Flux.empty();

        middleware.onAgent(agent, ctxOf("u1"), inputOf("我是小明"), emptyChain).blockLast();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(evolver, timeout(3000)).evolve(any(), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getRole()).isEqualTo(MsgRole.USER);
    }

    @Test
    @DisplayName("游标可读：order() 在审计之后")
    void orderAfterAudit() {
        assertThat(middleware.order()).isGreaterThan(10);
    }
}
