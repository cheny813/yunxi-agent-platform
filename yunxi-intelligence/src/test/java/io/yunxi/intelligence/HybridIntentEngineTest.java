package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.observability.AuditEvent;
import io.yunxi.observability.AuditSink;
import io.yunxi.observability.MetricRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 管线编排：短路（空文本/单候选/关键词显式目标）、LLM 判定与合并、降级与观测。 */
class HybridIntentEngineTest {

    private static final List<AgentCandidate> CANDIDATES = List.of(
            new AgentCandidate("nutrition", "营养师", "营养师：饮食建议"),
            new AgentCandidate("text2sql", "数据助手", "数据助手：数据库查询"),
            new AgentCandidate("general", "通用助手", "通用助手：闲聊问答"));

    private IntentProperties props() {
        IntentProperties p = new IntentProperties();
        p.setDefaultAgentId("general");
        return p;
    }

    private HybridIntentEngine engine(IntentProperties props, IntentClassifier classifier,
            List<AgentCandidate> candidates, RecordingMetrics metrics) {
        IntentTextNormalizer normalizer = new IntentTextNormalizer();
        return new HybridIntentEngine(
                normalizer,
                new SlotExtractor(Map.of()),
                (tenantId, limit) -> candidates,
                new KeywordIntentResolver(
                        Map.of("sql", List.of("报表")), Map.of("sql", "text2sql"), props.getDefaultAgentId(), normalizer),
                classifier,
                props,
                metrics,
                new RecordingAuditSink());
    }

    private static final class RecordingMetrics implements MetricRegistry {
        final Map<String, Long> counters = new HashMap<>();
        final Map<String, Double> gauges = new HashMap<>();

        @Override
        public void incrementCounter(String name) {
            counters.merge(name, 1L, Long::sum);
        }

        @Override
        public void recordGauge(String name, double value) {
            gauges.put(name, value);
        }
    }

    private static final class RecordingAuditSink implements AuditSink {
        final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void emit(AuditEvent event) {
            events.add(event);
        }
    }

    private static final class StubClassifier implements IntentClassifier {
        private final AtomicInteger calls = new AtomicInteger();
        private final IntentResolution answer;

        StubClassifier(IntentResolution answer) {
            this.answer = answer;
        }

        @Override
        public IntentResolution classify(String text, Map<String, String> entities, List<AgentCandidate> candidates) {
            calls.incrementAndGet();
            return answer;
        }
    }

    @Test
    void blankTextFallsBackWithoutModelCall() {
        RecordingMetrics metrics = new RecordingMetrics();
        StubClassifier classifier = new StubClassifier(null);
        HybridIntentEngine engine = engine(props(), classifier, CANDIDATES, metrics);

        IntentResolution r = engine.resolve(new IntentRequest("   ", "t1", "s1", 0));

        assertEquals("general", r.targetAgentId());
        assertEquals(IntentResolution.Source.FALLBACK, r.source());
        assertEquals(0, classifier.calls.get());
    }

    @Test
    void singleCandidateShortCircuitsWithoutModelCall() {
        RecordingMetrics metrics = new RecordingMetrics();
        StubClassifier classifier = new StubClassifier(null);
        List<AgentCandidate> only = List.of(new AgentCandidate("nutrition", "营养师", "营养师"));
        HybridIntentEngine engine = engine(props(), classifier, only, metrics);

        IntentResolution r = engine.resolve(new IntentRequest("随便说点什么", "t1", "s1", 0));

        assertEquals("nutrition", r.targetAgentId());
        assertEquals(IntentResolution.Source.KEYWORD, r.source());
        assertEquals(1.0d, r.confidence());
        assertEquals(0, classifier.calls.get());
    }

    @Test
    void keywordExplicitTargetShortCircuitsWithoutModelCall() {
        RecordingMetrics metrics = new RecordingMetrics();
        StubClassifier classifier = new StubClassifier(null);
        HybridIntentEngine engine = engine(props(), classifier, CANDIDATES, metrics);

        IntentResolution r = engine.resolve(new IntentRequest("帮我拉上月报表", "t1", "s1", 0));

        assertEquals("text2sql", r.targetAgentId());
        assertEquals(0, classifier.calls.get());
        assertEquals(1L, metrics.counters.getOrDefault("yunxi.intent.resolve.keyword", 0L));
    }

    @Test
    void classifierResultWinsWhenConfident() {
        RecordingMetrics metrics = new RecordingMetrics();
        StubClassifier classifier = new StubClassifier(new IntentResolution(
                "diet", 0.92, "nutrition", Map.of("orderId", "BAD"), "改写文本", IntentResolution.Source.LLM));
        HybridIntentEngine engine = engine(props(), classifier, CANDIDATES, metrics);

        IntentResolution r = engine.resolve(new IntentRequest("我血糖高晚饭吃什么 订单 A123456", "t1", "s1", 0));

        assertEquals("nutrition", r.targetAgentId());
        assertEquals("diet", r.label());
        assertEquals(IntentResolution.Source.LLM, r.source());
        // 实体合并规则值优先：规则抽到 A123456，模型给的 BAD 不覆盖。
        assertEquals("A123456", r.entities().get("orderId"));
    }

    @Test
    void classifierFailureDegradesToFallbackWithLlmFailCounter() {
        RecordingMetrics metrics = new RecordingMetrics();
        StubClassifier classifier = new StubClassifier(null);
        HybridIntentEngine engine = engine(props(), classifier, CANDIDATES, metrics);

        IntentResolution r = engine.resolve(new IntentRequest("今天天气怎么样", "t1", "s1", 0));

        assertEquals("general", r.targetAgentId());
        assertEquals(IntentResolution.Source.FALLBACK, r.source());
        assertEquals(1L, metrics.counters.getOrDefault("yunxi.intent.resolve.llm_fail", 0L));
    }

    @Test
    void tenantIdIsPassedDownToCandidateProvider() {
        RecordingMetrics metrics = new RecordingMetrics();
        List<String> seenTenants = new ArrayList<>();
        HybridIntentEngine engine = new HybridIntentEngine(
                new IntentTextNormalizer(),
                new SlotExtractor(Map.of()),
                (tenantId, limit) -> {
                    seenTenants.add(tenantId);
                    return CANDIDATES;
                },
                new KeywordIntentResolver(Map.of(), Map.of(), "general", new IntentTextNormalizer()),
                new StubClassifier(null),
                props(),
                metrics,
                new RecordingAuditSink());
        engine.resolve(new IntentRequest("hi", "tenant-9", "s1", 0));
        assertEquals("tenant-9", seenTenants.get(0));
    }

    @Test
    void emitsAuditEventAndLatencyGauge() {
        RecordingMetrics metrics = new RecordingMetrics();
        RecordingAuditSink audit = new RecordingAuditSink();
        HybridIntentEngine engine = new HybridIntentEngine(
                new IntentTextNormalizer(),
                new SlotExtractor(Map.of()),
                (tenantId, limit) -> CANDIDATES,
                new KeywordIntentResolver(Map.of(), Map.of(), "general", new IntentTextNormalizer()),
                null,
                props(),
                metrics,
                audit);

        engine.resolve(new IntentRequest("hello", "t1", "s1", 0));

        assertTrue(metrics.gauges.containsKey("yunxi.intent.latency.ms"));
        assertEquals(1, audit.events.size());
        AuditEvent event = audit.events.get(0);
        assertEquals("intent.resolve", event.type());
        assertEquals("t1", event.actor());
        assertEquals("general", event.payload().get("agentId"));
        assertNotNull(event.at(), "审计事件必须记录发生时刻");
    }

    @Test
    void keywordTargetOutsideCandidateSetIsIgnored() {
        RecordingMetrics metrics = new RecordingMetrics();
        IntentTextNormalizer normalizer = new IntentTextNormalizer();
        HybridIntentEngine engine = new HybridIntentEngine(
                normalizer,
                new SlotExtractor(Map.of()),
                (tenantId, limit) -> CANDIDATES,
                new KeywordIntentResolver(
                        Map.of("dsl", List.of("报表")), Map.of("dsl", "retired-agent"), "general", normalizer),
                null,
                props(),
                metrics,
                new RecordingAuditSink());

        IntentResolution resolution = engine.resolve(IntentRequest.of("帮我看下报表", null));

        assertEquals("general", resolution.targetAgentId(), "候选闭集外的规则目标必须被丢弃");
        assertEquals(IntentResolution.Source.FALLBACK, resolution.source());
        assertEquals(1L, metrics.counters.get("yunxi.intent.keyword.out_of_scope"));
    }
}
