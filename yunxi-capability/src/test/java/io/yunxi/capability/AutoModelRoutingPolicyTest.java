package io.yunxi.capability;

import io.agentscope.core.agent.RuntimeContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoModelRoutingPolicyTest {

    private ModelProperties props() {
        ModelProperties p = new ModelProperties();
        Map<String, ModelEntry> catalog = new HashMap<>();
        catalog.put("qwen3.8-flash", entry("dashscope", "qwen3.8-flash", "llm", "cheap", List.of("fast"), 131072));
        catalog.put("qwen3.8-omni-flash",
                entry("dashscope", "qwen3.8-omni-flash", "llm", "standard", List.of("vision", "video", "fast"), 131072));
        catalog.put("qwen-mt-uni", entry("dashscope", "qwen-mt-uni", "llm", "standard", List.of("vision", "multilingual"), 32768));
        catalog.put("qwen-coder", entry("dashscope", "qwen-coder", "llm", "standard", List.of("coding"), 32768));
        catalog.put("tev1", entry("ollama", "tev1:0.8b", "decision", null, null, null));
        p.setCatalog(catalog);
        p.setDefaults(Map.of("llm", "qwen3.8-flash"));
        AgentRoute autoRoute = new AgentRoute();
        autoRoute.setPrimary("auto");
        autoRoute.setFallbacks(List.of());
        p.setRouting(Map.of("auto-agent", autoRoute));
        return p;
    }

    private ModelEntry entry(String provider, String model, String type, String costTier,
                             List<String> strengths, Integer contextWindow) {
        ModelEntry e = new ModelEntry();
        e.setProvider(provider);
        e.setModel(model);
        e.setType(type);
        e.setCostTier(costTier);
        e.setStrengths(strengths);
        e.setContextWindow(contextWindow);
        e.setApiKeyEnv("X");
        return e;
    }

    private ConfigurableModelRoutingPolicy policy() {
        return new ConfigurableModelRoutingPolicy(props());
    }

    @Test
    void routeAutoReturnsAutoFlag() {
        RoutedModel r = policy().route("auto-agent", null, RuntimeContext.empty());
        assertTrue(r.auto());
        assertNull(r.primaryModelId());
    }

    @Test
    void textSelectsCheapest() {
        RoutedModel r = policy().resolveAuto("auto-agent", new TaskSignals(TaskSignals.MediaType.TEXT, 0, false, null));
        assertEquals("dashscope:qwen3.8-flash", r.primaryModelId());
    }

    @Test
    void imageSelectsMultimodalVisionFirst() {
        RoutedModel r = policy().resolveAuto("auto-agent", new TaskSignals(TaskSignals.MediaType.IMAGE, 0, false, null));
        assertEquals("dashscope:qwen3.8-omni-flash", r.primaryModelId());
        assertTrue(r.fallbackModelIds().contains("dashscope:qwen-mt-uni"));
    }

    @Test
    void videoSelectsVideoModel() {
        RoutedModel r = policy().resolveAuto("auto-agent", new TaskSignals(TaskSignals.MediaType.VIDEO, 0, false, null));
        assertEquals("dashscope:qwen3.8-omni-flash", r.primaryModelId());
    }

    @Test
    void toolHeavySelectsCodingModel() {
        RoutedModel r = policy().resolveAuto("auto-agent", new TaskSignals(TaskSignals.MediaType.TEXT, 0, true, null));
        assertEquals("dashscope:qwen-coder", r.primaryModelId());
    }

    @Test
    void longTextKeepsLargeWindow() {
        RoutedModel r = policy().resolveAuto("auto-agent", new TaskSignals(TaskSignals.MediaType.TEXT, 30000, false, null));
        // 估算 30000 token × 1.5 = 45000 窗口门槛，mt-uni/coder(32768) 被过滤，剩 flash/omni(131072)
        assertEquals("dashscope:qwen3.8-flash", r.primaryModelId());
    }

    @Test
    void defaultsTypeMismatchFailsFast() {
        ModelProperties p = new ModelProperties();
        p.setCatalog(Map.of("tev1", entry("ollama", "tev1:0.8b", "decision", null, null, null)));
        p.setDefaults(Map.of("llm", "tev1"));
        assertThrows(IllegalStateException.class, () -> new ConfigurableModelRoutingPolicy(p));
    }
}
