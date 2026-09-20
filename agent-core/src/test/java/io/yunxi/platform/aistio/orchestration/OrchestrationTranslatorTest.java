package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.shared.config.GraphConfig;
import io.yunxi.platform.shared.config.GraphEdgeConfig;
import io.yunxi.platform.shared.config.GraphNodeConfig;
import io.yunxi.platform.shared.config.JoinConfig;
import io.yunxi.platform.shared.config.TimerConfig;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestrationTranslatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private GraphNodeConfig agentNode(String key, String agentId) {
        GraphNodeConfig n = new GraphNodeConfig();
        n.setKey(key);
        n.setType("agent");
        n.setAgentId(agentId);
        return n;
    }

    private GraphEdgeConfig edge(String from, String to) {
        GraphEdgeConfig e = new GraphEdgeConfig();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    @Test
    void validGraph_translatesToArtifact() {
        GraphConfig graph = new GraphConfig();
        graph.setNodes(List.of(agentNode("extract", "11111111-1111-1111-1111-111111111111"),
                agentNode("audit", "22222222-2222-2222-2222-222222222222")));
        graph.setEdges(List.of(edge("extract", "audit")));

        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 200, 500);
        TranslationResult result = translator.translate(graph, "intake-v1", "1.0.0",
                new SetEntityRegistry(Set.of()));

        assertEquals(2, result.getSpec().getNodes().size());
        assertEquals(1, result.getSpec().getEdges().size());
        assertTrue(result.getManifest().getPrecheck().getHardErrors().isEmpty());
        assertTrue(result.getManifest().getPrecheck().getWarnings().isEmpty());
        assertEquals("intake-v1", result.getManifest().getDefinitionKey());
        assertFalse(result.getManifest().getSpecChecksum().isEmpty());
        assertTrue(result.getDefinitionJson().contains("\"extract\""));
    }

    @Test
    void duplicateNodeKey_failsFast() {
        GraphConfig graph = new GraphConfig();
        graph.setNodes(List.of(agentNode("extract", "11111111-1111-1111-1111-111111111111"),
                agentNode("extract", "22222222-2222-2222-2222-222222222222")));

        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 200, 500);
        OrchestrationSpecValidationException ex = assertThrows(
                OrchestrationSpecValidationException.class,
                () -> translator.translate(graph, "k", "1.0.0", new SetEntityRegistry(Set.of())));
        assertTrue(ex.getHardErrors().stream().anyMatch(i -> "NODE_KEY_DUP".equals(i.getRuleCode())));
    }

    @Test
    void invalidCel_failsFast() {
        GraphConfig graph = new GraphConfig();
        GraphNodeConfig cond = new GraphNodeConfig();
        cond.setKey("gate");
        cond.setType("condition");
        cond.setCondition("data.x >"); // 语法错误
        graph.setNodes(List.of(cond));

        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 200, 500);
        OrchestrationSpecValidationException ex = assertThrows(
                OrchestrationSpecValidationException.class,
                () -> translator.translate(graph, "k", "1.0.0", new SetEntityRegistry(Set.of())));
        assertTrue(ex.getHardErrors().stream().anyMatch(i -> "CEL_NOT_COMPILABLE".equals(i.getRuleCode())));
    }

    @Test
    void validCel_passes() {
        GraphConfig graph = new GraphConfig();
        GraphNodeConfig cond = new GraphNodeConfig();
        cond.setKey("gate");
        cond.setType("condition");
        cond.setCondition("data.amount > 100 && status == \"ok\"");
        graph.setNodes(List.of(cond));

        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 200, 500);
        TranslationResult result = translator.translate(graph, "k", "1.0.0", new SetEntityRegistry(Set.of()));
        assertTrue(result.getManifest().getPrecheck().getHardErrors().isEmpty());
    }

    @Test
    void entityNotRegistered_failsFast() {
        GraphConfig graph = new GraphConfig();
        graph.setNodes(List.of(agentNode("extract", "11111111-1111-1111-1111-111111111111")));

        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 200, 500);
        OrchestrationSpecValidationException ex = assertThrows(
                OrchestrationSpecValidationException.class,
                () -> translator.translate(graph, "k", "1.0.0",
                        new SetEntityRegistry(new HashSet<>(List.of("99999999-9999-9999-9999-999999999999")))));
        assertTrue(ex.getHardErrors().stream().anyMatch(i -> "ENTITY_NOT_REGISTERED".equals(i.getRuleCode())));
    }

    @Test
    void entityRegistered_passes() {
        GraphConfig graph = new GraphConfig();
        graph.setNodes(List.of(agentNode("extract", "11111111-1111-1111-1111-111111111111")));

        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 200, 500);
        TranslationResult result = translator.translate(graph, "k", "1.0.0",
                new SetEntityRegistry(Set.of("11111111-1111-1111-1111-111111111111")));
        assertTrue(result.getManifest().getPrecheck().getHardErrors().isEmpty());
    }

    @Test
    void oversizeGraph_warnsButExports() {
        GraphConfig graph = new GraphConfig();
        for (int i = 0; i < 3; i++) {
            graph.getNodes().add(agentNode("n" + i, String.format("%08d-%04d-%04d-%04d-%012d",
                    i, i, i, i, i)));
        }
        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 2, 500);
        TranslationResult result = translator.translate(graph, "k", "1.0.0", new SetEntityRegistry(Set.of()));
        assertFalse(result.getManifest().getPrecheck().getWarnings().isEmpty());
        assertTrue(result.getManifest().getPrecheck().getWarnings().stream()
                .anyMatch(w -> "GRAPH_SIZE".equals(w.getRuleCode())));
    }

    @Test
    void joinQuorum_invalid_failsFast() {
        GraphConfig graph = new GraphConfig();
        GraphNodeConfig join = new GraphNodeConfig();
        join.setKey("merge");
        join.setType("join");
        JoinConfig jc = new JoinConfig();
        jc.setMode("quorum");
        jc.setQuorum(5); // 入度为 0，应拒绝
        join.setJoin(jc);
        graph.setNodes(List.of(agentNode("a", "11111111-1111-1111-1111-111111111111"), join));

        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 200, 500);
        OrchestrationSpecValidationException ex = assertThrows(
                OrchestrationSpecValidationException.class,
                () -> translator.translate(graph, "k", "1.0.0", new SetEntityRegistry(Set.of())));
        assertTrue(ex.getHardErrors().stream().anyMatch(i -> "JOIN_QUORUM".equals(i.getRuleCode())));
    }

    @Test
    void cyclicGraph_failsFast() {
        GraphConfig graph = new GraphConfig();
        graph.setNodes(List.of(agentNode("a", "11111111-1111-1111-1111-111111111111"),
                agentNode("b", "22222222-2222-2222-2222-222222222222")));
        graph.setEdges(List.of(edge("a", "b"), edge("b", "a")));

        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 200, 500);
        OrchestrationSpecValidationException ex = assertThrows(
                OrchestrationSpecValidationException.class,
                () -> translator.translate(graph, "k", "1.0.0", new SetEntityRegistry(Set.of())));
        assertTrue(ex.getHardErrors().stream().anyMatch(i -> "DAG_CYCLE".equals(i.getRuleCode())));
    }

    @Test
    void timerRequiresAtOrDuration_failsFast() {
        GraphConfig graph = new GraphConfig();
        GraphNodeConfig timer = new GraphNodeConfig();
        timer.setKey("wait");
        timer.setType("timer");
        timer.setTimer(new TimerConfig()); // 既无 at 也无正 durationSeconds
        graph.setNodes(List.of(timer));

        OrchestrationTranslator translator = new OrchestrationTranslator(mapper, 200, 500);
        OrchestrationSpecValidationException ex = assertThrows(
                OrchestrationSpecValidationException.class,
                () -> translator.translate(graph, "k", "1.0.0", new SetEntityRegistry(Set.of())));
        assertTrue(ex.getHardErrors().stream().anyMatch(i -> "TIMER_REQ".equals(i.getRuleCode())));
    }
}
