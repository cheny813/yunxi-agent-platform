package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * JEV 云端判定分类器验证：成功映射、闭集校验失败回落、异常回落。
 * 回落链路遵循降级矩阵：JEV 失败 → 本地决策模型 → 关键词 → 默认。
 */
class JevIntentClassifierTest {

    private final IntentClassifier fallback =
            (text, entities, candidates) -> IntentResolution.fallback("agent-default");

    private final List<AgentCandidate> candidates = List.of(
            new AgentCandidate("agent-a", "营养助手", "营养助手：饮食记录与热量查询"),
            new AgentCandidate("agent-b", "数据助手", "数据助手：订单与库存查询"));

    @Test
    void mapsJevChoiceToResolution() {
        JevClient client = mock(JevClient.class);
        SystemOneResult result = new SystemOneResult("jev-latest",
                Map.of("intent", new ChoiceAnswer("agent-a",
                        Map.of("agent-a", 0.87, "agent-b", 0.10, "unknown", 0.03), 0.87)),
                null);
        when(client.systemOneBlocking(any())).thenReturn(result);

        JevIntentClassifier classifier = new JevIntentClassifier(client, fallback);
        IntentResolution resolution = classifier.classify("记一下午饭吃了鸡胸肉", Map.of(), candidates);

        assertEquals("agent-a", resolution.targetAgentId());
        assertEquals("营养助手", resolution.label());
        assertEquals(0.87, resolution.confidence(), 1e-9);
        assertEquals(IntentResolution.Source.LLM, resolution.source());
    }

    @Test
    void unknownChoiceReturnsNoTargetResolution() {
        JevClient client = mock(JevClient.class);
        SystemOneResult result = new SystemOneResult("jev-latest",
                Map.of("intent", new ChoiceAnswer("unknown",
                        Map.of("agent-a", 0.2, "unknown", 0.8), 0.8)),
                null);
        when(client.systemOneBlocking(any())).thenReturn(result);

        JevIntentClassifier classifier = new JevIntentClassifier(client, fallback);
        IntentResolution resolution = classifier.classify("hi", Map.of(), candidates);

        assertEquals(IntentResolution.UNKNOWN_AGENT_ID, resolution.targetAgentId());
        assertEquals(IntentResolution.Source.LLM, resolution.source());
    }

    @Test
    void fallsBackToDelegateWhenJevChoiceOutsideCandidates() {
        JevClient client = mock(JevClient.class);
        SystemOneResult result = new SystemOneResult("jev-latest",
                Map.of("intent", new ChoiceAnswer("agent-x", Map.of(), 0.99)),
                null);
        when(client.systemOneBlocking(any())).thenReturn(result);

        JevIntentClassifier classifier = new JevIntentClassifier(client, fallback);
        assertEquals(fallback.classify(null, null, null),
                classifier.classify("hi", Map.of(), candidates));
    }

    @Test
    void fallsBackToDelegateWhenJevErrors() {
        JevClient client = mock(JevClient.class);
        when(client.systemOneBlocking(any())).thenThrow(new RuntimeException("network down"));

        JevIntentClassifier classifier = new JevIntentClassifier(client, fallback);
        assertEquals(fallback.classify(null, null, null),
                classifier.classify("hi", Map.of(), candidates));
    }

    @Test
    void nullClientDelegatesToFallback() {
        JevIntentClassifier classifier = new JevIntentClassifier(null, fallback);
        assertEquals(fallback.classify(null, null, null),
                classifier.classify("hi", Map.of(), candidates));
    }

    @Test
    void emptyCandidatesDelegatesToFallback() {
        JevClient client = mock(JevClient.class);
        JevIntentClassifier classifier = new JevIntentClassifier(client, fallback);
        assertEquals(fallback.classify(null, null, null),
                classifier.classify("hi", Map.of(), List.of()));
    }
}
