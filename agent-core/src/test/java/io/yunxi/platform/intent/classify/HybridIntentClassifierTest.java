package io.yunxi.platform.intent.classify;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatUsage;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.intent.config.IntentProperties;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.intent.snapshot.EntityDictSnapshot;
import io.yunxi.platform.intent.snapshot.MappingSnapshot;
import io.yunxi.platform.intent.snapshot.TermSnapshot;
import io.yunxi.platform.intent.snapshot.TreeSnapshot;
import io.yunxi.platform.tracing.LlmMetrics;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HybridIntentClassifier} 编排单元测试。
 *
 * <p>覆盖混合编排全分支：规则高分直出 / LLM 命中 / 缓存命中 / 场景兜底 /
 * 白名单外拒绝 / LLM 异常降级 / llm 模式跳过规则。核心断言：任何失败不抛出、
 * 逐级降级、matchedBy 五态正确标记。</p>
 */
@DisplayName("HybridIntentClassifier 混合编排单元测试")
class HybridIntentClassifierTest {

    private IntentProperties properties;
    private RuleIntentClassifier ruleClassifier;
    private ModelFactory modelFactory;
    private LlmMetrics llmMetrics;
    private HybridIntentClassifier classifier;
    private DomainRuntime runtime;

    private Model model;
    private ChatResponse response;
    private TextBlock textBlock;
    private ChatUsage usage;

    @BeforeEach
    void setUp() {
        properties = new IntentProperties();
        properties.getClassification().setMode("hybrid");
        ruleClassifier = mock(RuleIntentClassifier.class);
        modelFactory = mock(ModelFactory.class);
        llmMetrics = mock(LlmMetrics.class);

        model = mock(Model.class);
        response = mock(ChatResponse.class);
        textBlock = mock(TextBlock.class);
        usage = mock(ChatUsage.class);
        when(modelFactory.create(any())).thenReturn(model);
        when(response.getContent()).thenReturn(List.of(textBlock));
        when(response.getUsage()).thenReturn(usage);

        classifier = new HybridIntentClassifier(properties, ruleClassifier, modelFactory,
                llmMetrics, new ObjectMapper());

        IntentTree.Node node = new IntentTree.Node();
        node.id = "order-query";
        node.label = "订单查询";
        IntentTree.Match match = new IntentTree.Match();
        match.anyKeywords = List.of("订单");
        node.match = match;
        TreeSnapshot tree = new TreeSnapshot(List.of(node), Map.of(node.id, node), Map.of(),
                List.of(node));
        runtime = new DomainRuntime("base", 1L, Instant.now(), EntityDictSnapshot.empty(),
                TermSnapshot.empty(), tree, MappingSnapshot.empty());
    }

    /** 构造 LLM 白名单命中场景：model 输出指定 JSON */
    private void stubLlm(String json) {
        when(textBlock.getText()).thenReturn(json);
        when(model.stream(anyList(), isNull(), isNull())).thenReturn(Flux.just(response));
    }

    private void stubRule(Intent intent) {
        when(ruleClassifier.classify(any(), anyList(), any(), any())).thenReturn(intent);
    }

    @Nested
    @DisplayName("hybrid 模式编排")
    class HybridMode {

        @Test
        @DisplayName("规则高分（≥阈值）直出，不触发 LLM")
        void ruleHighConfidenceDirect() {
            Intent rule = new Intent("order-query", "订单查询", 0.9, Intent.MATCHED_RULE);
            stubRule(rule);

            Intent result = classifier.classify("我的订单到哪了", List.of(), null, runtime);

            assertThat(result.intentId()).isEqualTo("order-query");
            assertThat(result.matchedBy()).isEqualTo(Intent.MATCHED_RULE);
            verify(modelFactory, never()).create(any());
        }

        @Test
        @DisplayName("规则低置信 → LLM 命中（matchedBy=LLM）")
        void ruleLowThenLlmHit() {
            stubRule(Intent.unknown());
            stubLlm("{\"intentId\":\"order-query\",\"confidence\":0.8,\"reason\":\"订单查询\"}");

            Intent result = classifier.classify("我的订单到哪了", List.of(), null, runtime);

            assertThat(result.intentId()).isEqualTo("order-query");
            assertThat(result.matchedBy()).isEqualTo(Intent.MATCHED_LLM);
            verify(modelFactory, times(1)).create(any());
        }

        @Test
        @DisplayName("同查询二次分类 → 缓存命中（matchedBy=LLM-CACHE），LLM 仅调用一次")
        void secondSameQueryCacheHit() {
            stubRule(Intent.unknown());
            stubLlm("{\"intentId\":\"order-query\",\"confidence\":0.8}");

            Intent first = classifier.classify("我的订单到哪了", List.of(), null, runtime);
            Intent second = classifier.classify("我的订单到哪了", List.of(), null, runtime);

            assertThat(first.matchedBy()).isEqualTo(Intent.MATCHED_LLM);
            assertThat(second.matchedBy()).isEqualTo(Intent.MATCHED_LLM_CACHE);
            verify(modelFactory, times(1)).create(any());
        }

        @Test
        @DisplayName("LLM 输出白名单外意图 → 拒绝并 RULE-FALLBACK 保底")
        void llmOutOfWhitelistRejected() {
            stubRule(Intent.unknown());
            stubLlm("{\"intentId\":\"hacker-intent\",\"confidence\":0.99}");

            Intent result = classifier.classify("我的订单到哪了", List.of(), null, runtime);

            assertThat(result.isUnknown()).isTrue();
            assertThat(result.matchedBy()).isEqualTo(Intent.MATCHED_RULE_FALLBACK);
        }

        @Test
        @DisplayName("LLM 调用异常 → 降级 RULE-FALLBACK，不抛出")
        void llmExceptionFallsBack() {
            stubRule(Intent.unknown());
            when(model.stream(anyList(), isNull(), isNull()))
                    .thenThrow(new RuntimeException("model down"));

            Intent result = classifier.classify("我的订单到哪了", List.of(), null, runtime);

            assertThat(result.isUnknown()).isTrue();
            assertThat(result.matchedBy()).isEqualTo(Intent.MATCHED_RULE_FALLBACK);
        }

        @Test
        @DisplayName("规则低置信但场景命中 → 三级链兜底直出（matchedBy=RULE）")
        void sceneFallbackDirect() {
            Intent rule = new Intent("order-query", "订单查询", 0.4, Intent.MATCHED_RULE);
            stubRule(rule);

            Intent result = classifier.classify("我的订单到哪了", List.of(), "SHOPPING", runtime);

            assertThat(result.intentId()).isEqualTo("order-query");
            assertThat(result.matchedBy()).isEqualTo(Intent.MATCHED_RULE);
            verify(modelFactory, never()).create(any());
        }

        @Test
        @DisplayName("规则低置信无场景 → 走 LLM")
        void ruleLowNoSceneGoesLlm() {
            Intent rule = new Intent("order-query", "订单查询", 0.4, Intent.MATCHED_RULE);
            stubRule(rule);
            stubLlm("{\"intentId\":\"order-query\",\"confidence\":0.8}");

            Intent result = classifier.classify("我的订单到哪了", List.of(), null, runtime);

            assertThat(result.matchedBy()).isEqualTo(Intent.MATCHED_LLM);
            verify(modelFactory, times(1)).create(any());
        }
    }

    @Nested
    @DisplayName("llm 模式")
    class LlmMode {

        @BeforeEach
        void setLlmMode() {
            properties.getClassification().setMode("llm");
        }

        @Test
        @DisplayName("跳过规则通道直接 LLM")
        void skipRuleDirectLlm() {
            stubLlm("{\"intentId\":\"order-query\",\"confidence\":0.9}");

            Intent result = classifier.classify("我的订单到哪了", List.of(), null, runtime);

            assertThat(result.intentId()).isEqualTo("order-query");
            assertThat(result.matchedBy()).isEqualTo(Intent.MATCHED_LLM);
            verify(ruleClassifier, never()).classify(any(), anyList(), isNull(), any());
        }

        @Test
        @DisplayName("LLM 未命中返回 unknown（matchedBy=NONE），不抛出")
        void llmMissReturnsUnknown() {
            stubLlm("{\"intentId\":\"unknown\",\"confidence\":0}");

            Intent result = classifier.classify("我的订单到哪了", List.of(), null, runtime);

            assertThat(result.isUnknown()).isTrue();
            assertThat(result.matchedBy()).isEqualTo(Intent.MATCHED_NONE);
        }
    }

    @Nested
    @DisplayName("detectSceneName 委托")
    class DetectScene {

        @Test
        @DisplayName("委托规则分类器，签名不变")
        void delegateToRule() {
            when(ruleClassifier.detectSceneName("查一下我上次的食谱")).thenReturn("FOOD");

            assertThat(classifier.detectSceneName("查一下我上次的食谱")).isEqualTo("FOOD");
            verify(ruleClassifier).detectSceneName("查一下我上次的食谱");
        }
    }
}
