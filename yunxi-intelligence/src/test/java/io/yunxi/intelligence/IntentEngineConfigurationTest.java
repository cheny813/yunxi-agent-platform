package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.observability.AuditSink;
import io.yunxi.observability.MetricRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 装配验证：总开关关闭时必须整体退回旧行为——既不读取候选集，也不调用分类模型，
 * 避免「关了开关仍产生模型调用」这类隐性成本与行为漂移。
 */
class IntentEngineConfigurationTest {

    @Test
    void disabledSwitchFallsBackWithoutTouchingPipeline() {
        IntentProperties properties = new IntentProperties();
        properties.setEnabled(false);
        properties.setDefaultAgentId("example-assistant");

        AtomicInteger candidateLookups = new AtomicInteger();
        AtomicInteger classifierCalls = new AtomicInteger();
        AgentCandidateProvider provider = (tenantId, limit) -> {
            candidateLookups.incrementAndGet();
            return List.of(new AgentCandidate("resident-nutrition-assistant", "营养师", "营养与饮食建议"));
        };
        IntentClassifier classifier = (text, entities, candidates) -> {
            classifierCalls.incrementAndGet();
            return new IntentResolution(
                    "nutrition", 0.9d, "resident-nutrition-assistant", Map.of(), text, IntentResolution.Source.LLM);
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("intentClassifier", classifier));

        IntentEngine engine = new IntentEngineConfiguration().intentEngine(
                properties,
                new IntentTextNormalizer(),
                new SlotExtractor(Map.of()),
                provider,
                beans.getBeanProvider(IntentClassifier.class),
                beans.getBeanProvider(MetricRegistry.class),
                beans.getBeanProvider(AuditSink.class));

        IntentResolution resolution = engine.resolve(IntentRequest.of("我最近血糖偏高，晚饭能吃什么", null));

        assertEquals("example-assistant", resolution.targetAgentId(), "关闭开关后目标必须是平台默认 Agent");
        assertEquals(IntentResolution.Source.FALLBACK, resolution.source());
        assertTrue(resolution.entities().isEmpty());
        assertEquals(0, candidateLookups.get(), "关闭开关时不得读取候选集");
        assertEquals(0, classifierCalls.get(), "关闭开关时不得调用分类模型");
    }
}
