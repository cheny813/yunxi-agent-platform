package io.yunxi.platform.intent.routing;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.agent.profile.ProfileRouter;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.intent.IntentResult;
import io.yunxi.platform.intent.RouteHint;
import io.yunxi.platform.intent.StageTimings;
import io.yunxi.platform.intent.config.IntentProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IntentAwareAgentResolver} 单元测试。
 *
 * <p>覆盖 M2.1 路由决策全部分支：命中改道 / 阈值拒绝 / 目标不存在回落 /
 * 开关关闭回落 / 无 hint 回落 / Profile 叠加。核心断言：advisory 原则——
 * 任何未满足条件的情况一律返回未改道，由调用方走默认解析链。</p>
 */
@DisplayName("IntentAwareAgentResolver 意图路由解析器单元测试")
class IntentAwareAgentResolverTest {

    private static final String BASE_AGENT = "general-assistant";
    private static final String ORDER_AGENT = "ecommerce-assistant";

    private IntentProperties properties;
    private AgentService agentService;
    private ProfileRouter profileRouter;
    private IntentAwareAgentResolver resolver;

    private Agent generalAgent;
    private Agent orderAgent;

    @BeforeEach
    void setUp() {
        properties = new IntentProperties();
        agentService = mock(AgentService.class);
        profileRouter = mock(ProfileRouter.class);
        resolver = new IntentAwareAgentResolver(properties, agentService, profileRouter);

        generalAgent = mock(Agent.class);
        orderAgent = mock(Agent.class);
    }

    private void enableRouting() {
        properties.getRouting().setEnabled(true);
    }

    private IntentResult intentResultOf(String suggestedAgent, double score) {
        return new IntentResult(
                "我的订单到哪了",
                "我的订单到哪了",
                List.of(),
                Intent.unknown(),
                new RouteHint(suggestedAgent, List.of("订单专家"), List.of("order"), List.of(), score),
                "GENERAL",
                new StageTimings(0, 0, 0, 0, 0),
                false);
    }

    private IntentResult intentResultWithoutHint() {
        return new IntentResult(
                "你好",
                "你好",
                List.of(),
                Intent.unknown(),
                RouteHint.empty(),
                "GENERAL",
                new StageTimings(0, 0, 0, 0, 0),
                false);
    }

    @Nested
    @DisplayName("未改道分支（advisory 降级安全）")
    class NotAdoptedBranches {

        /** 未改道决策的完整契约：adopted=false 且不携带任何 agent / 注入建议 */
        private void assertNotAdopted(RouteDecision decision) {
            assertThat(decision.adopted()).isFalse();
            assertThat(decision.agent()).isNull();
            assertThat(decision.experts()).isEmpty();
            assertThat(decision.toolGroups()).isEmpty();
            assertThat(decision.skills()).isEmpty();
        }

        @Test
        @DisplayName("开关关闭 → 未改道，即使 hint 完整命中")
        void disabledShouldNotAdopt() {
            properties.getRouting().setEnabled(false);
            when(agentService.getAgentInstance(ORDER_AGENT)).thenReturn(orderAgent);

            RouteDecision decision = resolver.resolve(BASE_AGENT, null, "u1",
                    intentResultOf(ORDER_AGENT, 0.95));

            assertNotAdopted(decision);
            verify(agentService, never()).getAgentInstance(ORDER_AGENT);
        }

        @Test
        @DisplayName("intentResult 为 null → 未改道")
        void nullIntentResultShouldNotAdopt() {
            enableRouting();

            RouteDecision decision = resolver.resolve(BASE_AGENT, null, "u1", null);

            assertNotAdopted(decision);
        }

        @Test
        @DisplayName("intentResult 非 null 但 routeHint 为 null → 未改道")
        void nullHintInResultShouldNotAdopt() {
            enableRouting();
            IntentResult noHint = new IntentResult(
                    "你好", "你好", List.of(), Intent.unknown(), null, "GENERAL",
                    new StageTimings(0, 0, 0, 0, 0), false);

            RouteDecision decision = resolver.resolve(BASE_AGENT, null, "u1", noHint);

            assertNotAdopted(decision);
        }

        @Test
        @DisplayName("routeHint 为空 → 未改道")
        void emptyHintShouldNotAdopt() {
            enableRouting();

            RouteDecision decision = resolver.resolve(BASE_AGENT, null, "u1",
                    intentResultWithoutHint());

            assertNotAdopted(decision);
        }

        @Test
        @DisplayName("分数低于门槛 → 未改道")
        void lowScoreShouldNotAdopt() {
            enableRouting();
            properties.getRouting().setMinRouteScore(0.8);
            when(agentService.getAgentInstance(ORDER_AGENT)).thenReturn(orderAgent);

            RouteDecision decision = resolver.resolve(BASE_AGENT, null, "u1",
                    intentResultOf(ORDER_AGENT, 0.6));

            assertNotAdopted(decision);
            verify(agentService, never()).getAgentInstance(ORDER_AGENT);
        }

        @Test
        @DisplayName("目标 agent 不存在（getAgentInstance 抛异常）→ 未改道保持原路由")
        void agentNotFoundShouldNotAdopt() {
            enableRouting();
            when(agentService.getAgentInstance(ORDER_AGENT))
                    .thenThrow(new RuntimeException("Agent not found: " + ORDER_AGENT));

            RouteDecision decision = resolver.resolve(BASE_AGENT, null, "u1",
                    intentResultOf(ORDER_AGENT, 0.95));

            assertNotAdopted(decision);
        }
    }

    @Nested
    @DisplayName("命中改道分支")
    class AdoptedBranches {

        @Test
        @DisplayName("命中且达标 → 改道，携带注入建议")
        void hitShouldAdopt() {
            enableRouting();
            when(agentService.getAgentInstance(ORDER_AGENT)).thenReturn(orderAgent);

            RouteDecision decision = resolver.resolve(BASE_AGENT, null, "u1",
                    intentResultOf(ORDER_AGENT, 0.95));

            assertThat(decision.adopted()).isTrue();
            assertThat(decision.agent()).isSameAs(orderAgent);
            assertThat(decision.experts()).containsExactly("订单专家");
            assertThat(decision.toolGroups()).containsExactly("order");
            assertThat(decision.skills()).isEmpty();
            verify(agentService).getAgentInstance(ORDER_AGENT);
        }

        @Test
        @DisplayName("分数等于门槛 → 改道（边界：>= 判定）")
        void scoreEqualToThresholdShouldAdopt() {
            enableRouting();
            properties.getRouting().setMinRouteScore(0.5);
            when(agentService.getAgentInstance(ORDER_AGENT)).thenReturn(orderAgent);

            RouteDecision decision = resolver.resolve(BASE_AGENT, null, "u1",
                    intentResultOf(ORDER_AGENT, 0.5));

            assertThat(decision.adopted()).isTrue();
            assertThat(decision.agent()).isSameAs(orderAgent);
        }

        @Test
        @DisplayName("命中且 profile 非空 → 目标 agent 仍尊重 Profile 路由")
        void hitWithProfileShouldRespectProfileRouter() {
            enableRouting();
            when(agentService.getAgentInstance(ORDER_AGENT)).thenReturn(orderAgent);
            when(profileRouter.resolve(ORDER_AGENT, "business")).thenReturn(generalAgent);

            RouteDecision decision = resolver.resolve(BASE_AGENT, "business", "u1",
                    intentResultOf(ORDER_AGENT, 0.95));

            assertThat(decision.adopted()).isTrue();
            assertThat(decision.agent()).isSameAs(generalAgent);
            verify(profileRouter).resolve(ORDER_AGENT, "business");
        }

        @Test
        @DisplayName("命中且 profile 为空 → 直接使用目标 agent，不经过 Profile 路由")
        void hitWithBlankProfileShouldUseTargetDirectly() {
            enableRouting();
            when(agentService.getAgentInstance(ORDER_AGENT)).thenReturn(orderAgent);

            RouteDecision decision = resolver.resolve(BASE_AGENT, "  ", "u1",
                    intentResultOf(ORDER_AGENT, 0.95));

            assertThat(decision.adopted()).isTrue();
            assertThat(decision.agent()).isSameAs(orderAgent);
            verify(profileRouter, never()).resolve(ORDER_AGENT, "  ");
        }
    }
}
