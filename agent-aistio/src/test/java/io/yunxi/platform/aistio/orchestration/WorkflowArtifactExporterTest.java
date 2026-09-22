package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.GraphConfig;
import io.yunxi.platform.shared.config.GraphEdgeConfig;
import io.yunxi.platform.shared.config.GraphNodeConfig;
import io.yunxi.platform.shared.config.MountBodyConfig;
import io.yunxi.platform.shared.config.MountConfig;
import io.yunxi.platform.shared.config.OrchestrationConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端导出验证：直接驱动 {@link WorkflowArtifactExporter#export} 将编排图落盘为
 * definition.json + manifest.json，并断言产物结构与分级预校验行为（HARD 跳过 / WARN 仍导出）。
 */
public class WorkflowArtifactExporterTest {

    private static final String UUID_A = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_B = "22222222-2222-2222-2222-222222222222";

    private static GraphNodeConfig node(String key, String type) {
        GraphNodeConfig n = new GraphNodeConfig();
        n.setKey(key);
        n.setType(type);
        return n;
    }

    private static GraphNodeConfig agentNode(String key, String uuid) {
        GraphNodeConfig n = node(key, "agent");
        n.setAgentId(uuid);
        return n;
    }

    private static GraphConfig validGraph() {
        GraphConfig g = new GraphConfig();
        GraphNodeConfig start = agentNode("start", UUID_A);
        GraphNodeConfig branch = node("branch", "condition");
        branch.setCondition("status == 'ok'");
        GraphNodeConfig end = agentNode("end", UUID_B);
        g.setNodes(List.of(start, branch, end));
        GraphEdgeConfig e1 = new GraphEdgeConfig();
        e1.setFrom("start");
        e1.setTo("branch");
        GraphEdgeConfig e2 = new GraphEdgeConfig();
        e2.setFrom("branch");
        e2.setTo("end");
        g.setEdges(List.of(e1, e2));
        return g;
    }

    private static MountConfig workflowMountInline(String name, GraphConfig graph) {
        MountConfig m = new MountConfig();
        m.setName(name);
        MountBodyConfig body = new MountBodyConfig();
        body.setKind("workflow");
        body.setGraph(graph);
        m.setBody(body);
        return m;
    }

    private static MountConfig workflowMountRef(String name, String definitionKey) {
        MountConfig m = new MountConfig();
        m.setName(name);
        MountBodyConfig body = new MountBodyConfig();
        body.setKind("workflow");
        body.setDefinition(definitionKey);
        m.setBody(body);
        return m;
    }

    private static AgentDefinition defWithMounts(List<MountConfig> mounts, GraphConfig topLevelGraph) {
        AgentDefinition def = new AgentDefinition();
        def.setName("export-test-agent");
        def.setEnabled(true);
        OrchestrationConfig oc = new OrchestrationConfig();
        oc.setMounts(mounts);
        oc.setGraph(topLevelGraph);
        def.setOrchestration(oc);
        return def;
    }

    private static WorkflowArtifactExporter newExporter() {
        return new WorkflowArtifactExporter(new AgentDefinitionLoader(), new AistioIntegrationProperties(),
                new ObjectMapper());
    }

    @Test
    void export_writesDefinitionAndManifest(@TempDir Path dir) throws Exception {
        AgentDefinition def = defWithMounts(List.of(workflowMountInline("d1", validGraph())), null);
        OrchestrationTranslator translator = new OrchestrationTranslator(new ObjectMapper(), 200, 500);
        EntityRegistry registry = new SetEntityRegistry(Set.of());

        List<Path> written = newExporter().export(List.of(def), dir, translator, registry, "1.0.0-test");

        Path defPath = dir.resolve("d1.definition.json");
        Path manifestPath = dir.resolve("d1.manifest.json");
        assertTrue(Files.exists(defPath), "definition.json 应写出");
        assertTrue(Files.exists(manifestPath), "manifest.json 应写出");
        assertEquals(2, written.size());

        String defJson = Files.readString(defPath);
        assertTrue(defJson.contains("\"nodes\""), "definition.json 应含 nodes");
        assertTrue(defJson.contains("\"edges\""), "definition.json 应含 edges");

        Manifest manifest = new ObjectMapper().readValue(manifestPath.toFile(), Manifest.class);
        assertEquals("d1", manifest.getDefinitionKey());
        assertEquals("1.0.0-test", manifest.getTranslatorVersion());
        assertNotNull(manifest.getSpecChecksum(), "manifest 应含 spec SHA-256");
        assertTrue(manifest.getPrecheck().getHardErrors().isEmpty(), "合法图不应有 hardErrors");
        assertTrue(manifest.getPrecheck().getWarnings().isEmpty(), "合法图不应有 warnings");
        assertTrue(manifest.getReferencedEntities().getAgentIds().contains(UUID_A));
    }

    @Test
    void export_oversizeGraph_stillWritesWithWarning(@TempDir Path dir) throws Exception {
        // 用更小的上限（3 节点）触发 WARN：构建 4 个 agent 节点的图
        List<GraphNodeConfig> nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            nodes.add(agentNode("n" + i, UUID_A));
        }
        GraphConfig g = new GraphConfig();
        g.setNodes(nodes);
        AgentDefinition def = defWithMounts(List.of(workflowMountInline("big", g)), null);
        OrchestrationTranslator translator = new OrchestrationTranslator(new ObjectMapper(), 3, 500);
        EntityRegistry registry = new SetEntityRegistry(Set.of());

        newExporter().export(List.of(def), dir, translator, registry, "1.0.0-test");

        Path manifestPath = dir.resolve("big.manifest.json");
        assertTrue(Files.exists(manifestPath), "超规模图仍应导出 manifest");
        assertTrue(Files.exists(dir.resolve("big.definition.json")), "超规模图仍应导出 definition");
        // warnings 内含有 ValidationIssue 实例，manifest 是给运维读取的文本契约，这里直接校验 JSON 内容
        String manifestJson = Files.readString(manifestPath);
        assertTrue(manifestJson.contains("GRAPH_SIZE"), "超规模图应在 manifest 记录 GRAPH_SIZE");
        assertTrue(manifestJson.contains("\"warnings\""), "超规模图应在 manifest.warnings 留痕");
        assertTrue(manifestJson.contains("\"hardErrors\":[]") || manifestJson.contains("\"hardErrors\": []"),
                "超规模仅 WARN，不应有 hardErrors");
    }

    @Test
    void export_invalidGraph_isSkipped(@TempDir Path dir) throws Exception {
        GraphConfig g = validGraph();
        // 注入一条 CEL 语法错误：condition 节点表达式不完整
        g.getNodes().stream().filter(n -> "condition".equals(n.getType())).forEach(n -> n.setCondition("status =="));
        AgentDefinition def = defWithMounts(List.of(workflowMountInline("bad", g)), null);
        OrchestrationTranslator translator = new OrchestrationTranslator(new ObjectMapper(), 200, 500);
        EntityRegistry registry = new SetEntityRegistry(Set.of());

        List<Path> written = newExporter().export(List.of(def), dir, translator, registry, "1.0.0-test");

        assertTrue(written.isEmpty(), "HARD 违规图不应写出任何文件");
        assertFalse(Files.exists(dir.resolve("bad.definition.json")));
        assertFalse(Files.exists(dir.resolve("bad.manifest.json")));
    }

    @Test
    void export_resolvesTopLevelGraphByReference(@TempDir Path dir) throws Exception {
        // 挂载只填 body.definition 引用顶层 orchestration.graph，不内联 graph
        AgentDefinition def = defWithMounts(List.of(workflowMountRef("refed", "my-def")), validGraph());
        OrchestrationTranslator translator = new OrchestrationTranslator(new ObjectMapper(), 200, 500);
        EntityRegistry registry = new SetEntityRegistry(Set.of());

        newExporter().export(List.of(def), dir, translator, registry, "1.0.0-test");

        Path defPath = dir.resolve("my-def.definition.json");
        assertTrue(Files.exists(defPath), "引用顶层图的挂载应解析并导出");
        Manifest manifest = new ObjectMapper().readValue(dir.resolve("my-def.manifest.json").toFile(), Manifest.class);
        assertEquals("my-def", manifest.getDefinitionKey());
    }
}
