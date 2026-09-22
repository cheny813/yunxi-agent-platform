package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.OrchestrationConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证：直接读取资源目录下的独立示例 YAML（运维照抄的格式范本），
 * 经翻译器翻译并由导出器写到磁盘，断言导出的 definition.json / manifest.json 结构正确。
 * <p>本测试不依赖 Spring 启动，但完整跑通「YAML -> 翻译 -> 落盘 artifact」全链路，
 * 等价于把示例交给生产导出器处理后的产物。</p>
 */
class WorkflowArtifactExporterE2ETest {

    // 独立示例 YAML 在 agent-config 主资源目录；测试从文件系统读取同一份，
    // 避免依赖已安装的 agent-config 构件，也保证断言的就是运维照抄的那份。
    private static final List<Path> EXAMPLE_CANDIDATES = List.of(
            Paths.get("..", "agent-config", "src", "main", "resources",
                    "agent-definitions", "workflow-export-example.yml"),
            Paths.get("agent-config", "src", "main", "resources",
                    "agent-definitions", "workflow-export-example.yml"));

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule());

    @Test
    void exampleYamlLoadsAndExportsEndToEnd() throws Exception {
        // 1) 定位并读取独立示例（与运维照抄的文件是同一份）
        Path example = EXAMPLE_CANDIDATES.stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new AssertionError("示例 YAML 未找到，候选: " + EXAMPLE_CANDIDATES));
        var wrapper = yamlMapper.readValue(
                Files.newInputStream(example), AgentDefinitionLoader.AgentDefinitionWrapper.class);
        AgentDefinition def = wrapper.getAgent();
        assertNotNull(def);

        // 范本以 enabled:false 存在，不会被 AgentDefinitionLoader 当成真实 Agent 加载
        assertFalse(def.isEnabled(), "示例应标记 enabled:false，仅作格式范本");
        OrchestrationConfig oc = def.getOrchestration();
        assertNotNull(oc);
        assertEquals(2, oc.getMounts().size(), "示例应含两个 workflow 挂载（内联 + 引用顶层图）");

        // 2) 构造实体注册表：节点引用的 agent 须在本环境已注册，否则 E3 阻断导出
        Set<String> registered = new HashSet<>(List.of(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333"));
        EntityRegistry registry = new SetEntityRegistry(registered);
        OrchestrationTranslator translator = new OrchestrationTranslator(new ObjectMapper(), 200, 500);

        // 3) 真正跑导出器公共方法，把 artifact 写到临时目录
        WorkflowArtifactExporter exporter = new WorkflowArtifactExporter(
                null, new AistioIntegrationProperties(), new ObjectMapper());
        Path dir = Files.createTempDirectory("wf-e2e");
        List<Path> written = exporter.export(List.of(def), dir, translator, registry, "1.0.0");

        // 4) 断言产物：每个挂载导出 definition.json + manifest.json
        assertEquals(4, written.size(), "两个挂载共应写出 4 个文件");
        Path inlineDef = dir.resolve("purchase-review.definition.json");
        Path inlineManifest = dir.resolve("purchase-review.manifest.json");
        Path refDef = dir.resolve("order-fulfillment.definition.json");
        Path refManifest = dir.resolve("order-fulfillment.manifest.json");
        for (Path p : List.of(inlineDef, inlineManifest, refDef, refManifest)) {
            assertTrue(Files.exists(p), "应写出: " + p.getFileName());
        }

        // 5) 校验内联图 definition.json 结构
        DefinitionSpec spec = new ObjectMapper().readValue(inlineDef.toFile(), DefinitionSpec.class);
        assertEquals(5, spec.getNodes().size());
        assertEquals(5, spec.getEdges().size());
        assertTrue(spec.getNodes().stream()
                .anyMatch(n -> "approval".equals(n.getType()) && n.getApproval() != null),
                "应含人工审批节点且保留 approval 配置");

        // 6) 校验 manifest 关键字段
        Manifest manifest = new ObjectMapper().readValue(inlineManifest.toFile(), Manifest.class);
        assertEquals("purchase-review", manifest.getDefinitionKey());
        assertEquals("1.0.0", manifest.getTranslatorVersion());
        assertNotNull(manifest.getSpecChecksum(), "manifest 应含 spec SHA-256 校验和");
        assertTrue(manifest.getPrecheck().getHardErrors().isEmpty(), "无 HARD 违规应有空 hardErrors");
        assertTrue(manifest.getReferencedEntities().getAgentIds()
                .contains("11111111-1111-1111-1111-111111111111"),
                "manifest 应记录被引用的 agentId");

        // 7) 引用顶层图的挂载同样解析并落盘（其节点来自 orchestration.graph）
        DefinitionSpec refSpec = new ObjectMapper().readValue(refDef.toFile(), DefinitionSpec.class);
        assertEquals(6, refSpec.getNodes().size());
        assertEquals(6, refSpec.getEdges().size());
        assertTrue(refSpec.getNodes().stream().anyMatch(n -> "signal".equals(n.getType())),
                "顶层图应含 signal 节点，证明引用路径已解析");
    }
}
