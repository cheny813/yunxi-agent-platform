package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 决策融合：越权拒绝、低置信回落（保留 label）、unknown 无匹配、关键词与默认兜底。 */
class IntentDeciderTest {

    private static final List<AgentCandidate> CANDIDATES = List.of(
            new AgentCandidate("nutrition", "营养师", "营养师：饮食建议"),
            new AgentCandidate("text2sql", "数据助手", "数据助手：数据库查询"));

    private final IntentDecider decider = new IntentDecider();

    private IntentDecision llm(String agentId, String label, double confidence) {
        return new IntentDecision(
                new IntentResolution(label, confidence, agentId, Map.of(), null, IntentResolution.Source.LLM),
                IntentDecider.LLM_PROPOSED);
    }

    @Test
    void acceptsInCandidateWithConfidenceAboveThreshold() {
        IntentDecision d = decider.decide(
                llm("nutrition", "diet", 0.9), null, "default", 0.6, CANDIDATES);
        assertEquals(IntentDecider.Outcome.LLM_ACCEPTED, d.outcome());
        assertEquals("nutrition", d.resolution().targetAgentId());
        assertEquals(IntentResolution.Source.LLM, d.resolution().source());
    }

    @Test
    void rejectsAgentIdOutsideCandidates() {
        IntentDecision d = decider.decide(
                llm("hacker-agent", "x", 0.99), null, "default", 0.6, CANDIDATES);
        assertEquals(IntentDecider.Outcome.REJECTED_NOT_CANDIDATE, d.outcome());
        assertEquals("default", d.resolution().targetAgentId());
    }

    @Test
    void lowConfidenceFallsBackButKeepsLabel() {
        IntentDecision d = decider.decide(llm("nutrition", "diet", 0.3), null, "default", 0.6, CANDIDATES);
        assertEquals(IntentDecider.Outcome.LOW_CONFIDENCE, d.outcome());
        assertEquals("default", d.resolution().targetAgentId());
        assertEquals("diet", d.resolution().label());
        assertEquals(IntentResolution.Source.FALLBACK, d.resolution().source());
    }

    @Test
    void unknownAgentIdMeansNoMatch() {
        IntentDecision d = decider.decide(llm(IntentResolution.UNKNOWN_AGENT_ID, "chat", 0.8), null, "default", 0.6, CANDIDATES);
        assertEquals(IntentDecider.Outcome.NO_MATCH, d.outcome());
        assertEquals("default", d.resolution().targetAgentId());
    }

    @Test
    void keywordResultUsedWhenClassifierFailed() {
        IntentDecision kw = new IntentDecision(
                new IntentResolution("sql", 1.0, "text2sql", Map.of(), null, IntentResolution.Source.KEYWORD),
                IntentDecider.Outcome.KEYWORD);
        IntentDecision d = decider.decide(null, kw, "default", 0.6, CANDIDATES);
        assertEquals(IntentDecider.Outcome.KEYWORD, d.outcome());
        assertEquals("text2sql", d.resolution().targetAgentId());
    }

    @Test
    void plainFallbackWhenEverythingFailed() {
        IntentDecision d = decider.decide(null, null, "default", 0.6, CANDIDATES);
        assertEquals(IntentDecider.Outcome.FALLBACK, d.outcome());
        assertEquals("default", d.resolution().targetAgentId());
        assertEquals(IntentResolution.UNKNOWN_LABEL, d.resolution().label());
    }
}
