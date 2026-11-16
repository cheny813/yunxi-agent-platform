package io.yunxi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.definition.DefaultAgentSpecValidator;
import io.yunxi.intelligence.AgentCandidateProvider;
import io.yunxi.intelligence.ConfigCenterAgentCandidateProvider;
import io.yunxi.intelligence.HybridIntentEngine;
import io.yunxi.intelligence.IntentClassifier;
import io.yunxi.intelligence.IntentEngine;
import io.yunxi.intelligence.IntentProperties;
import io.yunxi.intelligence.IntentRequest;
import io.yunxi.intelligence.IntentResolution;
import io.yunxi.intelligence.IntentTextNormalizer;
import io.yunxi.intelligence.KeywordIntentResolver;
import io.yunxi.intelligence.LocalDecisionIntentClassifier;
import io.yunxi.intelligence.SlotExtractor;
import io.yunxi.runtime.AgentDefinitionLoader;
import io.yunxi.runtime.TenantAgentAllocationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 意图路由端到端验证：真实 Agent 定义（配置中心目录）+ 真实候选提供者 / 提示词 / 分类器 / 引擎，
 * 仅把大模型替换为桩模型。验证的是「语义判定结果决定目标 Agent」这条链路本身——
 * 桩模型按用户输入给出判定，引擎必须照此路由，而非依赖关键词子串命中。
 */
class IntentRoutingE2eTest {

    private static final String NUTRITION = "resident-nutrition-assistant";
    private static final String TEXT2SQL = "text2sql-assistant";
    private static final String CODER = "example-coder";
    private static final String DEFAULT_AGENT = "example-assistant";

    private InMemoryConfigCenter configCenter;
    private AgentCandidateProvider candidates;
    private IntentEngine engine;

    @BeforeEach
    void buildPipeline() {
        configCenter = new InMemoryConfigCenter();
        new AgentDefinitionLoader(configCenter, new DefaultAgentSpecValidator()).load();

        IntentProperties properties = new IntentProperties();
        properties.setDefaultAgentId(DEFAULT_AGENT);

        IntentTextNormalizer normalizer = new IntentTextNormalizer();
        candidates = new ConfigCenterAgentCandidateProvider(
                configCenter, new TenantAgentAllocationService(configCenter));
        IntentClassifier classifier = new LocalDecisionIntentClassifier(
                modelId -> new StubDecisionModel(),
                properties.getClassifier().getModelId(),
                properties.getClassifier().getTimeoutMillis(),
                properties.getClassifier().getMaxTokens(),
                properties.getMaxInputChars(),
                false,
                new ObjectMapper());
        engine = new HybridIntentEngine(
                normalizer,
                new SlotExtractor(Map.of()),
                candidates,
                new KeywordIntentResolver(Map.of(), Map.of(), DEFAULT_AGENT, normalizer),
                classifier,
                properties,
                null,
                null);
    }

    @Test
    void loadsRealAgentDefinitionsAsClosedCandidateSet() {
        List<String> ids = candidates.candidates(null, 0).stream().map(c -> c.agentId()).toList();

        assertEquals(4, ids.size(), "候选闭集必须来自真实 Agent 定义目录");
        assertTrue(ids.containsAll(List.of(NUTRITION, TEXT2SQL, CODER, DEFAULT_AGENT)), ids.toString());
    }

    @Test
    void semanticVerdictDecidesTargetAcrossDomains() {
        IntentResolution nutrition = engine.resolve(IntentRequest.of("我最近血糖偏高，晚饭能吃什么", null));
        assertEquals(NUTRITION, nutrition.targetAgentId());
        assertEquals(IntentResolution.Source.LLM, nutrition.source());

        assertEquals(TEXT2SQL, engine.resolve(IntentRequest.of("把订单表里上个月的 GMV 拉出来", null)).targetAgentId());
        assertEquals(CODER, engine.resolve(IntentRequest.of("帮我修一下这个 Java 空指针", null)).targetAgentId());
    }

    @Test
    void unmatchedInputFallsBackToDefaultAgent() {
        IntentResolution resolution = engine.resolve(IntentRequest.of("今天天气怎么样", null));

        assertEquals(DEFAULT_AGENT, resolution.targetAgentId());
        assertEquals(IntentResolution.Source.FALLBACK, resolution.source());
    }

    /**
     * 桩决策模型：只读提示词中用 {@code <<< >>>} 包裹的用户输入区，按领域返回判定 JSON。
     * 候选清单片段不参与判断，避免把「候选卡里出现的词」误当成用户意图。
     */
    private static final class StubDecisionModel implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String input = userInput(messages.isEmpty() ? "" : messages.get(0).getTextContent());
            return Flux.just(ChatResponse.builder()
                    .id("stub-decision")
                    .content(List.of(TextBlock.builder().text(verdict(input)).build()))
                    .usage(new ChatUsage(1, 1, 2))
                    .build());
        }

        @Override
        public String getModelName() {
            return "stub-decision-model";
        }

        private static String userInput(String prompt) {
            int start = prompt.indexOf("<<<\n");
            int end = prompt.indexOf("\n>>>");
            return start >= 0 && end > start ? prompt.substring(start + 4, end) : prompt;
        }

        private static String verdict(String input) {
            if (input.contains("血糖")) {
                return json(NUTRITION, "营养配餐", 0.93d);
            }
            if (input.contains("GMV") || input.contains("订单表")) {
                return json(TEXT2SQL, "数据查询", 0.91d);
            }
            if (input.contains("空指针") || input.contains("Java")) {
                return json(CODER, "代码修复", 0.88d);
            }
            return json(IntentResolution.UNKNOWN_AGENT_ID, IntentResolution.UNKNOWN_LABEL, 0.15d);
        }

        private static String json(String agentId, String label, double confidence) {
            return "{\"agentId\":\"" + agentId + "\",\"label\":\"" + label + "\",\"confidence\":" + confidence
                    + ",\"entities\":{},\"rewrittenQuery\":\"\"}";
        }
    }
}
