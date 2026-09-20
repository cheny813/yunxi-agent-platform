package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试（固化自端到端验证）：打开 {@code yunxi.aistio.workflow.export-enabled=true}，
 * 由真实 Spring 上下文触发 {@link WorkflowArtifactExporter#exportOnStartup()}（@PostConstruct），
 * 对 classpath 中 {@code enabled: true} 的 workflow Agent 定义真正执行导出并落盘。
 *
 * <p>与生产启动走完全相同的代码路径：Spring 容器 refresh 时先触发
 * {@link AgentDefinitionLoader#loadConfigs()} 加载 agent 定义，随后触发
 * {@link WorkflowArtifactExporter#exportOnStartup()} 扫描 workflow 挂载并翻译导出。</p>
 *
 * <p>运行方式（本模块默认跳过集成测试，需 -Pintegration 激活）：
 * {@code mvn -pl agent-integration-test -am test -Pintegration -Dtest=WorkflowExportStartupIntegrationTest}</p>
 */
class WorkflowExportStartupIntegrationTest {

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    @Test
    void exportEnabledTrueExportsRealWorkflowAgentOnStartup() throws Exception {
        Path dir = Path.of("target", "workflow-artifacts-it");
        // 幂等：先清空上次产物，保证每次运行从干净状态验证
        if (Files.exists(dir)) {
            try (var s = Files.walk(dir)) {
                s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 忽略单次删除失败，下一轮继续
                    }
                });
            }
        }

        var envProps = new MapPropertySource("verify", Map.of(
                "yunxi.aistio.workflow.export-enabled", "true",
                "yunxi.aistio.workflow.export-dir", dir.toAbsolutePath().toString()));

        // 真实 Spring 上下文：refresh() 触发 loader.loadConfigs() 与 exporter.exportOnStartup()
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(envProps);
            ctx.register(VerifyConfig.class);
            ctx.refresh();
        }

        // —— 挂载一：内联图 purchase-review ——
        Path prDef = dir.resolve("purchase-review.definition.json");
        Path prMan = dir.resolve("purchase-review.manifest.json");
        assertTrue(Files.exists(prDef), "内联图 definition.json 应已导出");
        assertTrue(Files.exists(prMan), "内联图 manifest.json 应已导出");

        ObjectMapper om = new ObjectMapper();
        DefinitionSpec prSpec = om.readValue(prDef.toFile(), DefinitionSpec.class);
        assertEquals(5, prSpec.getNodes().size(), "内联图节点数");
        assertEquals(5, prSpec.getEdges().size(), "内联图边数");
        DefinitionNode approve = prSpec.getNodes().stream()
                .filter(n -> "approve".equals(n.getKey())).findFirst().orElseThrow();
        assertEquals("approval", approve.getType());
        assertNotNull(approve.getApproval());
        assertEquals("请复核该采购单", approve.getApproval().getPrompt());

        Manifest prManifest = om.readValue(prMan.toFile(), Manifest.class);
        assertEquals("purchase-review", prManifest.getDefinitionKey());
        assertEquals("1.0.0", prManifest.getTranslatorVersion());
        assertTrue(HEX64.matcher(prManifest.getSpecChecksum()).matches(), "specChecksum 应为 64 位 hex");
        assertTrue(prManifest.getPrecheck().getHardErrors().isEmpty(), "预校验无硬阻断");
        assertTrue(prManifest.getReferencedEntities().getAgentIds()
                .contains("11111111-1111-1111-1111-111111111111"));
        assertTrue(prManifest.getReferencedEntities().getAgentIds()
                .contains("22222222-2222-2222-2222-222222222222"));

        // —— 挂载二：引用顶层 orchestration.graph（definitionKey = top）——
        Path topDef = dir.resolve("top.definition.json");
        Path topMan = dir.resolve("top.manifest.json");
        assertTrue(Files.exists(topDef), "顶层图引用 definition.json 应已导出");
        assertTrue(Files.exists(topMan), "顶层图引用 manifest.json 应已导出");
        DefinitionSpec topSpec = om.readValue(topDef.toFile(), DefinitionSpec.class);
        assertEquals(5, topSpec.getNodes().size(), "顶层图节点数");
        assertEquals(5, topSpec.getEdges().size(), "顶层图边数");
        Manifest topManifest = om.readValue(topMan.toFile(), Manifest.class);
        assertEquals("top", topManifest.getDefinitionKey());
        assertTrue(topManifest.getPrecheck().getHardErrors().isEmpty(), "顶层图预校验无硬阻断");
    }

    /**
     * 回归保护：resident-nutrition-assistant.yml 内联的 supervisor 编排下挂载了一份
     * 营养配餐 workflow（nutrition-recipe-review）。打开导出后它必须能被翻译且预校验无硬阻断，
     * 证明这份声明式 workflow 定义是「可用的」（可被 WorkflowArtifactExporter 导出为 aistio artifact）。
     */
    @Test
    void nutritionAssistantWorkflowMountExportsWithoutHardErrors() throws Exception {
        Path dir = Path.of("target", "workflow-artifacts-it-nutrition");
        if (Files.exists(dir)) {
            try (var s = Files.walk(dir)) {
                s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 忽略单次删除失败
                    }
                });
            }
        }

        var envProps = new MapPropertySource("verify-nutrition", Map.of(
                "yunxi.aistio.workflow.export-enabled", "true",
                "yunxi.aistio.workflow.export-dir", dir.toAbsolutePath().toString()));

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(envProps);
            ctx.register(VerifyConfig.class);
            ctx.refresh();
        }

        Path def = dir.resolve("nutrition-recipe-review.definition.json");
        Path man = dir.resolve("nutrition-recipe-review.manifest.json");
        assertTrue(Files.exists(def), "营养 workflow definition.json 应已导出");
        assertTrue(Files.exists(man), "营养 workflow manifest.json 应已导出");

        ObjectMapper om = new ObjectMapper();
        DefinitionSpec spec = om.readValue(def.toFile(), DefinitionSpec.class);
        assertEquals(6, spec.getNodes().size(), "营养 workflow 节点数");
        assertEquals(6, spec.getEdges().size(), "营养 workflow 边数");
        Manifest manifest = om.readValue(man.toFile(), Manifest.class);
        assertEquals("nutrition-recipe-review", manifest.getDefinitionKey());
        assertTrue(manifest.getPrecheck().getHardErrors().isEmpty(), "营养 workflow 预校验无硬阻断");
        assertTrue(manifest.getReferencedEntities().getAgentIds()
                .contains("b1b1b1b1-b1b1-b1b1-b1b1-b1b1b1b1b1b1"));
    }

    @Configuration
    @EnableConfigurationProperties
    static class VerifyConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        AgentDefinitionLoader agentDefinitionLoader() {
            return new AgentDefinitionLoader();
        }

        @Bean
        AistioIntegrationProperties aistioIntegrationProperties() {
            return new AistioIntegrationProperties();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        WorkflowArtifactExporter workflowArtifactExporter(AgentDefinitionLoader loader,
                AistioIntegrationProperties props, ObjectMapper objectMapper) {
            return new WorkflowArtifactExporter(loader, props, objectMapper);
        }
    }
}
