package io.yunxi.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 能力层路由验证：{@link DefaultModelRoutingPolicy} 是静态路由，按 agentId 查预加载映射。
 * 命中返回配置的主模型+兜底；未命中回退为主模型=agentId 本身、空兜底；intent 与上下文不影响结果。
 */
class DefaultModelRoutingPolicyTest {

    private final RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("s").build();

    @Test
    void knownAgent_returnsConfiguredRoute() {
        var policy = new DefaultModelRoutingPolicy(
                Map.of("agent-a", new RoutedModel("qwen-plus", List.of("qwen-turbo"))));
        RoutedModel r = policy.route("agent-a", "chat", ctx);
        assertEquals("qwen-plus", r.primaryModelId());
        assertEquals(List.of("qwen-turbo"), r.fallbackModelIds());
    }

    @Test
    void unknownAgent_fallsBackToAgentIdAsPrimaryWithEmptyFallback() {
        var policy = new DefaultModelRoutingPolicy(Map.of());
        RoutedModel r = policy.route("agent-x", "chat", ctx);
        assertEquals("agent-x", r.primaryModelId());
        assertTrue(r.fallbackModelIds().isEmpty(), "未知 agent 兜底列表必须为空");
    }

    @Test
    void intentAndContext_doNotAffectRouting() {
        var policy = new DefaultModelRoutingPolicy(
                Map.of("agent-a", new RoutedModel("m1", List.of("m2"))));
        RoutedModel r1 = policy.route("agent-a", "intent-1", ctx);
        RoutedModel r2 = policy.route("agent-a", "intent-2", ctx);
        assertEquals(r1, r2, "静态路由不随 intent 变化");
    }
}
