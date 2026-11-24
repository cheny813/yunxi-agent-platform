package io.yunxi.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ConfigurableModelRoutingPolicy} 覆盖：自动选型（能力过滤 + 成本升序 + 长文本放宽）、
 * 固定 / auto / 未声明路由分派、框架 id 解析、以及构造期启动快速失败校验。
 */
class ConfigurableModelRoutingPolicyTest {

    private static final String P = "dashscope";

    private ModelEntry entry(String provider, String model, String type, String costTier,
                             List<String> strengths, Integer ctx) {
        ModelEntry e = new ModelEntry();
        e.setProvider(provider);
        e.setModel(model);
        e.setType(type);
        if (costTier != null) {
            e.setCostTier(costTier);
        }
        e.setStrengths(strengths);
        e.setContextWindow(ctx);
        return e;
    }

    private ModelProperties props(Map<String, ModelEntry> catalog, Map<String, String> defaults,
                                  Map<String, AgentRoute> routing) {
        ModelProperties p = new ModelProperties();
        p.setCatalog(catalog);
        p.setDefaults(defaults);
        p.setRouting(routing);
        return p;
    }

    private ModelProperties sampleProperties() {
        Map<String, ModelEntry> catalog = new HashMap<>();
        catalog.put("cheap", entry(P, "qwen-cheap", "llm", "cheap", List.of(), 8_000));
        catalog.put("std", entry(P, "qwen-std", "llm", "standard", List.of("coding"), 32_000));
        catalog.put("vision", entry(P, "qwen-vision", "llm", "premium", List.of("vision"), 32_000));
        catalog.put("video", entry(P, "qwen-video", "llm", "premium", List.of("video"), 64_000));
        Map<String, String> defaults = new HashMap<>();
        defaults.put("llm", "cheap");
        return props(catalog, defaults, new HashMap<>());
    }

    @Test
    void localLlmIsRejectedAtStartup() {
        ModelProperties p = sampleProperties();
        p.getCatalog().put("local-llm", entry("ollama", "qwen3:8b", "llm", "cheap", List.of(), 8_000));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ConfigurableModelRoutingPolicy(p));
        assertTrue(ex.getMessage().contains("allow-local-llm"), "错误信息必须指明豁免开关");
    }

    @Test
    void localLlmAllowedWithExplicitExemptionFlag() {
        ModelProperties p = sampleProperties();
        p.getCatalog().put("local-llm", entry("ollama", "qwen3:8b", "llm", "cheap", List.of(), 8_000));
        p.setAllowLocalLlm(true);
        assertNotNull(new ConfigurableModelRoutingPolicy(p));
    }

    @Test
    void remoteLlmPassesTopologyValidation() {
        assertNotNull(new ConfigurableModelRoutingPolicy(sampleProperties()));
    }

    @Test
    void resolveAutoPicksCheapestAmongAllLlmWhenNoConstraint() {
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(sampleProperties());
        TaskSignals signals = new TaskSignals(TaskSignals.MediaType.TEXT, 0, false, null);

        RoutedModel r = policy.resolveAuto("agent", signals);

        assertFalse(r.auto());
        assertEquals("dashscope:qwen-cheap", r.primaryModelId());
        // 余下候选按成本升序 + 同档上下文窗口降序兜底。
        assertEquals(List.of("dashscope:qwen-std", "dashscope:qwen-video", "dashscope:qwen-vision"),
                r.fallbackModelIds());
    }

    @Test
    void resolveAutoFiltersByVideoStrength() {
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(sampleProperties());
        TaskSignals signals = new TaskSignals(TaskSignals.MediaType.VIDEO, 0, false, null);

        RoutedModel r = policy.resolveAuto("agent", signals);

        assertEquals("dashscope:qwen-video", r.primaryModelId());
        assertTrue(r.fallbackModelIds().isEmpty());
    }

    @Test
    void resolveAutoFiltersByCodingStrengthOnToolHeavy() {
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(sampleProperties());
        TaskSignals signals = new TaskSignals(TaskSignals.MediaType.TEXT, 0, true, null);

        RoutedModel r = policy.resolveAuto("agent", signals);

        assertEquals("dashscope:qwen-std", r.primaryModelId());
    }

    @Test
    void resolveAutoFiltersShortContextWindowForLongText() {
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(sampleProperties());
        // 估算 token 20000，安全系数 1.5 -> 窗口不足 30000 的 cheap 被过滤。
        TaskSignals signals = new TaskSignals(TaskSignals.MediaType.TEXT, 20_000, false, null);

        RoutedModel r = policy.resolveAuto("agent", signals);

        assertEquals("dashscope:qwen-std", r.primaryModelId());
    }

    @Test
    void resolveAutoRelaxesToAllLlmWhenNoCapabilityMatch() {
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(sampleProperties());
        // 视频信号但池中没有 video 能力项时，放宽到全部 llm 取最便宜，避免无候选。
        TaskSignals signals = new TaskSignals(TaskSignals.MediaType.VIDEO, 0, false, null);
        Map<String, ModelEntry> catalog = new HashMap<>();
        catalog.put("std", entry(P, "qwen-std", "llm", "standard", List.of("coding"), 32_000));
        ModelProperties props = props(catalog, Map.of("llm", "std"), new HashMap<>());

        ConfigurableModelRoutingPolicy relaxed = new ConfigurableModelRoutingPolicy(props);
        RoutedModel r = relaxed.resolveAuto("agent", signals);

        assertEquals("dashscope:qwen-std", r.primaryModelId());
    }

    @Test
    void routeFixedPrimaryResolvesFrameworkIdAndFallbacks() {
        ModelProperties props = sampleProperties();
        AgentRoute route = new AgentRoute();
        route.setPrimary("cheap");
        route.setFallbacks(List.of("std"));
        props.setRouting(Map.of("agentX", route));
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(props);

        RoutedModel r = policy.route("agentX", "intent", null);

        assertFalse(r.auto());
        assertEquals("dashscope:qwen-cheap", r.primaryModelId());
        assertEquals(List.of("dashscope:qwen-std"), r.fallbackModelIds());
    }

    @Test
    void routeAutoPrimaryReturnsAutoRoutedModel() {
        ModelProperties props = sampleProperties();
        AgentRoute route = new AgentRoute();
        route.setPrimary(ConfigurableModelRoutingPolicy.AUTO);
        props.setRouting(Map.of("agentX", route));
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(props);

        RoutedModel r = policy.route("agentX", "intent", null);

        assertTrue(r.auto());
        assertEquals(null, r.primaryModelId());
    }

    @Test
    void routeUnspecifiedAgentDefaultsToAuto() {
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(sampleProperties());

        RoutedModel r = policy.route("unknownAgent", "intent", null);

        assertTrue(r.auto());
    }

    @Test
    void routeNullPrimaryThrows() {
        ModelProperties props = sampleProperties();
        AgentRoute route = new AgentRoute();
        route.setPrimary(null);
        props.setRouting(Map.of("agentX", route));
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(props);

        assertThrows(IllegalStateException.class, () -> policy.route("agentX", "intent", null));
    }

    @Test
    void toFrameworkIdJoinsProviderAndModel() {
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(sampleProperties());

        assertEquals("dashscope:qwen-video", policy.toFrameworkId("video"));
    }

    @Test
    void toFrameworkIdThrowsForUndefinedLogicalName() {
        ConfigurableModelRoutingPolicy policy = new ConfigurableModelRoutingPolicy(sampleProperties());

        assertThrows(IllegalStateException.class, () -> policy.toFrameworkId("ghost"));
    }

    @Test
    void constructorFailsWhenDefaultsPointsToMissingCatalogEntry() {
        Map<String, ModelEntry> catalog = new HashMap<>();
        catalog.put("cheap", entry(P, "qwen-cheap", "llm", "cheap", List.of(), 8_000));
        ModelProperties props = props(catalog, Map.of("llm", "ghost"), new HashMap<>());

        assertThrows(IllegalStateException.class, () -> new ConfigurableModelRoutingPolicy(props));
    }

    @Test
    void constructorFailsWhenDefaultsTypeInconsistent() {
        Map<String, ModelEntry> catalog = new HashMap<>();
        catalog.put("dec", entry(P, "qwen-dec", "decision", "standard", List.of(), null));
        ModelProperties props = props(catalog, Map.of("llm", "dec"), new HashMap<>());

        // defaults.llm 必须指向 type=llm 的目录项，本地决策模型不得误用作对话默认。
        assertThrows(IllegalStateException.class, () -> new ConfigurableModelRoutingPolicy(props));
    }

    @Test
    void constructorFailsWhenNoLlmCandidate() {
        Map<String, ModelEntry> catalog = new HashMap<>();
        catalog.put("dec", entry(P, "qwen-dec", "decision", "standard", List.of(), null));
        ModelProperties props = props(catalog, Map.of("decision", "dec"), new HashMap<>());

        assertThrows(IllegalStateException.class, () -> new ConfigurableModelRoutingPolicy(props));
    }
}
