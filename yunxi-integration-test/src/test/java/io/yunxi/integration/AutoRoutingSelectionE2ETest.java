package io.yunxi.integration;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.yunxi.capability.ConfigurableModelRoutingPolicy;
import io.yunxi.capability.ModelEntry;
import io.yunxi.capability.ModelProperties;
import io.yunxi.runtime.AutoModelProvider;
import io.yunxi.runtime.ModelFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 自动选型端到端验证：直接驱动 {@code auto-assistant} 实际使用的 {@link AutoModelProvider}，
 * 复用 {@code application.yml} 中真实的模型 catalog / routing，仅把最终模型解析替换为 recording 桩
 * （不发起真实 API 调用、不依赖任何环境变量或外部服务）。
 *
 * <p>通过构造携带 {@link ImageBlock} / {@link VideoBlock} 内容块的请求消息，验证任务信号提取
 * （{@code TaskSignals.from}）与候选排序（能力满足 + 成本最低）是否正确选中多模态模型；
 * 纯文本请求作为对照，应选中成本档最低的文本模型。
 *
 * <p>这对应「向 auto-assistant 发含图 / 视频请求，断言选中多模态模型」的验收点；模型解析路径与
 * 固定路由完全一致（同一 {@link ModelFactory}），因此本测试即 auto-assistant 选型子系统的端到端。
 */
class AutoRoutingSelectionE2ETest {

    /** 纯文本请求预期选中的最便宜文本模型（catalog 中 cost-tier=cheap 的唯一项）。 */
    private static final String TEXT_PRIMARY = "openai:qwen3.8-flash";
    /** 含图 / 视频请求预期选中的多模态模型（同时具备 vision / video 能力）。 */
    private static final String MULTIMODAL_PRIMARY = "openai:qwen3.8-omni-flash";

    private final RecordingModelFactory factory = new RecordingModelFactory();
    private final AutoModelProvider provider =
            new AutoModelProvider("auto-assistant", loadPolicy(), factory);

    @BeforeEach
    void reset() {
        factory.clear();
    }

    @Test
    void textRequestSelectsCheapestTextLlm() {
        provider.stream(List.of(textMsg("请帮我写一首关于春天的小诗")), null, GenerateOptions.builder().build());
        assertEquals(TEXT_PRIMARY, factory.first(),
                "纯文本请求应选中成本档最低的纯文本模型，而非多模态模型");
    }

    @Test
    void imageRequestSelectsVisionCapableLlm() {
        provider.stream(List.of(imageMsg("请描述这张图片里的场景")), null, GenerateOptions.builder().build());
        assertEquals(MULTIMODAL_PRIMARY, factory.first(),
                "含图请求应选中具备 vision 能力的多模态模型");
    }

    @Test
    void videoRequestSelectsVideoCapableLlm() {
        provider.stream(List.of(videoMsg("请总结这段视频讲的是什么")), null, GenerateOptions.builder().build());
        assertEquals(MULTIMODAL_PRIMARY, factory.first(),
                "含视频请求应选中具备 video 能力的多模态模型（候选池中仅 qwen3.8-omni-flash 满足）");
    }

    // ---- 消息构造：与 TaskSignals.from 的媒体识别保持一致（ImageBlock / VideoBlock 内容块）----

    private static Msg textMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Msg imageMsg(String text) {
        return Msg.builder().role(MsgRole.USER).content(
                TextBlock.builder().text(text).build(),
                new ImageBlock(new URLSource("https://example.com/sample.png")))
                .build();
    }

    private static Msg videoMsg(String text) {
        return Msg.builder().role(MsgRole.USER).content(
                TextBlock.builder().text(text).build(),
                new VideoBlock(new URLSource("https://example.com/sample.mp4")))
                .build();
    }

    // ---- 从 classpath 上的真实 application.yml 绑定 yunxi.models（catalog / routing / defaults）----

    /**
     * 从 classpath 上的真实 application.yml 读取 {@code yunxi.models} 段，构建选型 catalog。
     *
     * <p>用 SnakeYAML 直接解析嵌套结构（而非 {@code YamlPropertiesFactoryBean} 扁平化），
     * 以保留模型名中的点号（如 {@code qwen3.8-flash}）——扁平化会把点号误当作嵌套分隔符，
     * 导致含点号的 catalog 项丢失。这样测试使用的是与运行时一致的真实 catalog 值。
     */
    @SuppressWarnings("unchecked")
    private static ConfigurableModelRoutingPolicy loadPolicy() {
        Yaml yaml = new Yaml();
        // yunxi.models 经 spring.config.import 聚合在 config/models.yml（不在主 application.yml 顶层），
        // 故直接读取该资源，保留模型名中的点号（扁平化会把点号误当作嵌套分隔符）。
        try (InputStream in = new ClassPathResource("config/models.yml").getInputStream()) {
            Map<String, Object> root = yaml.load(in);
            Map<String, Object> yunxi = (Map<String, Object>) root.get("yunxi");
            Map<String, Object> models = (Map<String, Object>) yunxi.get("models");

            Map<String, ModelEntry> entries = new LinkedHashMap<>();
            Map<String, Object> catalog = (Map<String, Object>) models.get("catalog");
            for (Map.Entry<String, Object> e : catalog.entrySet()) {
                Map<String, Object> m = (Map<String, Object>) e.getValue();
                ModelEntry me = new ModelEntry();
                me.setProvider(asStr(m, "provider"));
                me.setModel(asStr(m, "model"));
                me.setApiKeyEnv(asStr(m, "api-key-env"));
                me.setType(asStr(m, "type", "llm"));
                me.setCostTier(asStr(m, "cost-tier", "standard"));
                me.setStrengths((List<String>) m.get("strengths"));
                me.setContextWindow((Integer) m.get("context-window"));
                me.setLatencyTier(asStr(m, "latency-tier"));
                me.setMaxOutputTokens((Integer) m.get("max-output-tokens"));
                entries.put(e.getKey(), me);
            }

            Map<String, String> defaults = new LinkedHashMap<>();
            Map<String, Object> defaultsSrc = (Map<String, Object>) models.get("defaults");
            defaultsSrc.forEach((k, v) -> defaults.put(k, String.valueOf(v)));

            ModelProperties mp = new ModelProperties();
            mp.setCatalog(entries);
            mp.setDefaults(defaults);
            return new ConfigurableModelRoutingPolicy(mp);
        } catch (IOException ex) {
            throw new IllegalStateException("无法加载 application.yml 以构建选型 catalog", ex);
        }
    }

    private static String asStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String asStr(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    // ---- recording 桩：截获 AutoModelProvider 实际解析（create）的模型 id 序列 ----

    /** 记录每次 {@code create(modelId)} 调用顺序；{@link #first()} 即自动选型选中的主模型。 */
    private static final class RecordingModelFactory implements ModelFactory {
        private final List<String> created = Collections.synchronizedList(new ArrayList<>());

        void clear() {
            created.clear();
        }

        String first() {
            return created.isEmpty() ? null : created.get(0);
        }

        List<String> all() {
            return List.copyOf(created);
        }

        @Override
        public Model create(String modelId) {
            created.add(modelId);
            return new StubModel(modelId);
        }
    }

    /** 不做真实推理的桩模型：stream 立即返回一条空响应，仅用于让选型路径完整跑通。 */
    private static final class StubModel implements Model {
        private final String name;

        StubModel(String name) {
            this.name = name;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(new ChatResponse("stub", List.of(), null, Map.of(), "stop"));
        }

        @Override
        public String getModelName() {
            return name;
        }
    }
}
