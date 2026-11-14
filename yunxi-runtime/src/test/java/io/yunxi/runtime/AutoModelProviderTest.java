package io.yunxi.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.yunxi.capability.ConfigurableModelRoutingPolicy;
import io.yunxi.capability.ModelEntry;
import io.yunxi.capability.ModelProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutoModelProvider} 的聚焦单测（离线，无真实大模型）：验证自动选型两条核心语义——文本信号选中最便宜候选，
 * 以及元数据正确反映 auto 标识与最大上下文窗口。测试用 {@code RecordingModel} / {@code FakeFactory} 桩件避免外部 API 依赖。
 */
class AutoModelProviderTest {

    static final class RecordingModel implements Model {
        final String name;
        boolean streamed = false;

        RecordingModel(String name) {
            this.name = name;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            streamed = true;
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    static final class FakeFactory implements ModelFactory {
        final Map<String, RecordingModel> map = new HashMap<>();

        @Override
        public Model create(String id) {
            return map.get(id);
        }
    }

    private ConfigurableModelRoutingPolicy policy() {
        ModelProperties p = new ModelProperties();
        Map<String, ModelEntry> catalog = new HashMap<>();
        catalog.put("flash", e("dashscope", "qwen3.8-flash", "llm", "cheap", List.of("fast"), 131072));
        catalog.put("omni", e("dashscope", "qwen3.8-omni-flash", "llm", "standard", List.of("vision", "video"), 131072));
        p.setCatalog(catalog);
        p.setDefaults(Map.of("llm", "flash"));
        return new ConfigurableModelRoutingPolicy(p);
    }

    private ModelEntry e(String provider, String model, String type, String cost, List<String> s, Integer cw) {
        ModelEntry me = new ModelEntry();
        me.setProvider(provider);
        me.setModel(model);
        me.setType(type);
        me.setCostTier(cost);
        me.setStrengths(s);
        me.setContextWindow(cw);
        me.setApiKeyEnv("X");
        return me;
    }

    @Test
    void textDelegatesToCheapestCandidate() {
        ConfigurableModelRoutingPolicy pol = policy();
        FakeFactory f = new FakeFactory();
        RecordingModel flash = new RecordingModel("dashscope:qwen3.8-flash");
        RecordingModel omni = new RecordingModel("dashscope:qwen3.8-omni-flash");
        f.map.put("dashscope:qwen3.8-flash", flash);
        f.map.put("dashscope:qwen3.8-omni-flash", omni);

        AutoModelProvider ap = new AutoModelProvider("auto-assistant", pol, f);
        ap.stream(List.of(), List.of(), null).blockLast();

        assertTrue(flash.streamed, "文本信号应选中最便宜的 flash 候选");
        assertFalse(omni.streamed, "omni 不应被选中");
    }

    @Test
    void metadataReflectsAutoAndMaxWindow() {
        ConfigurableModelRoutingPolicy pol = policy();
        FakeFactory f = new FakeFactory();
        f.map.put("dashscope:qwen3.8-flash", new RecordingModel("dashscope:qwen3.8-flash"));

        AutoModelProvider ap = new AutoModelProvider("auto-assistant", pol, f);
        assertEquals("auto:auto-assistant", ap.getModelName());
        assertEquals(131072, ap.getContextWindowSize());
    }
}
