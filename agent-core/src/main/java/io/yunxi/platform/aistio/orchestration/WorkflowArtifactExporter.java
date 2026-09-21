package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.aistio.client.AistioControlPlaneClient;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.GraphConfig;
import io.yunxi.platform.shared.config.MountBodyConfig;
import io.yunxi.platform.shared.config.MountConfig;
import io.yunxi.platform.shared.config.OrchestrationConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编排图 artifact 导出器：启动期扫描所有 agent 定义的 workflow 挂载，
 * 将内联图校验并导出为 aistio DefinitionSpec artifact（definition.json + manifest.json），
 * 供控制台导入。默认关闭（{@code yunxi.aistio.workflow.export-enabled=false}）。
 *
 * <p>开启 {@code push-enabled} 后，导出完成会在后台线程把每个 definition.json 自动推送至 aistio
 * 控制面（{@code POST /api/v1/orchestration-definitions}），从而消除控制台手工导入这一步。
 * 推送采用控制台账号令牌（PAT，{@code Authorization: Bearer}），由 {@link AistioIntegrationProperties.WorkflowExportConfig#getPushToken()}
 * 配置。推送失败仅 WARN 并有限重试，<b>绝不阻塞启动</b>。</p>
 *
 * <p>推送走统一的 {@link AistioControlPlaneClient}（适配层）：控制面地址、鉴权头、超时与重试策略集中于一处，
 * PAT 作为方法参数传入，不固化为共享状态（凭据一律作参数、不外渗产品平面核心）。</p>
 */
@Service
public class WorkflowArtifactExporter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowArtifactExporter.class);

    private static final String DEFINITION_SUFFIX = ".definition.json";

    private final AgentDefinitionLoader loader;
    private final AistioIntegrationProperties props;
    private final ObjectMapper objectMapper;
    private final AistioControlPlaneClient client;

    public WorkflowArtifactExporter(AgentDefinitionLoader loader, AistioIntegrationProperties props,
            ObjectMapper objectMapper) {
        this.loader = loader;
        this.props = props;
        this.objectMapper = objectMapper;
        this.client = new AistioControlPlaneClient(props.getControlPlaneUrl(), objectMapper);
    }

    @PostConstruct
    public void exportOnStartup() {
        AistioIntegrationProperties.WorkflowExportConfig cfg = props.getWorkflow();
        log.info("enter exportOnStartup: exportEnabled={} pushEnabled={} tokenBlank={} controlPlaneUrl={}",
                cfg.isExportEnabled(), cfg.isPushEnabled(),
                (cfg.getPushToken() == null || cfg.getPushToken().isBlank()), props.getControlPlaneUrl());
        if (!cfg.isExportEnabled()) {
            return;
        }
        List<AgentDefinition> defs = loader.getAgentDefinitions();
        EntityRegistry registry = new SetEntityRegistry(Set.copyOf(cfg.getRegisteredEntityIds()));
        OrchestrationTranslator translator =
                new OrchestrationTranslator(objectMapper, 200, 500);
        Path dir = Path.of(cfg.getExportDir());
        List<Path> written = export(defs, dir, translator, registry, cfg.getTranslatorVersion());
        if (written.isEmpty()) {
            log.info("编排图导出：未找到可导出的内联 workflow 挂载");
            return;
        }
        log.info("编排图导出完成，共写出 {} 个 artifact 到 {}", written.size(), dir);
        pushIfEnabled(written);
    }

    /**
     * 遍历 agent 定义，导出所有内联 workflow 挂载。
     *
     * @return 写出的文件路径列表（definition.json 与 manifest.json 各算一项）
     */
    public List<Path> export(List<AgentDefinition> defs, Path dir, OrchestrationTranslator translator,
            EntityRegistry registry, String translatorVersion) {
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.error("创建导出目录失败: {}", dir, e);
            return written;
        }
        for (AgentDefinition def : defs) {
            OrchestrationConfig oc = def.getOrchestration();
            if (oc == null || oc.getMounts() == null) {
                continue;
            }
            for (MountConfig mount : oc.getMounts()) {
                MountBodyConfig body = mount.getBody();
                // kind 大小写不敏感：统一转小写后再比对，避免 kind: WORKFLOW 等大写在导出时被跳过
                if (body == null || body.getKind() == null || !"workflow".equals(body.getKind().toLowerCase())) {
                    continue;
                }
                // graph 来源：优先使用挂载内联的 body.graph；否则若 body.definition 引用了顶层 orchestration.graph，取其内容
                GraphConfig graph = body.getGraph();
                String definitionKey = body.getDefinition() != null ? body.getDefinition() : mount.getName();
                if (graph == null) {
                    if (body.getDefinition() != null && oc.getGraph() != null) {
                        graph = oc.getGraph();
                    } else {
                        continue;
                    }
                }
                try {
                    TranslationResult result = translator.translate(graph, definitionKey,
                            translatorVersion, registry);
                    Path defPath = dir.resolve(sanitize(definitionKey) + DEFINITION_SUFFIX);
                    Path manifestPath = dir.resolve(sanitize(definitionKey) + ".manifest.json");
                    Files.writeString(defPath, result.getDefinitionJson());
                    Files.writeString(manifestPath, objectMapper.writeValueAsString(result.getManifest()));
                    written.add(defPath);
                    written.add(manifestPath);
                    log.info("导出编排图 artifact: {} (key={})", defPath, definitionKey);
                } catch (OrchestrationSpecValidationException e) {
                    log.error("挂载 {} 预校验未通过，跳过导出：{}", definitionKey, e.getMessage());
                } catch (Exception e) {
                    log.error("导出挂载 {} 失败", definitionKey, e);
                }
            }
        }
        return written;
    }

    /**
     * 导出完成后，把每个 definition.json 后台推送至 aistio 控制面（开启且已配置 PAT 时）。
     * 推送经 {@link AistioControlPlaneClient}，凭据作参数，超时重试统一。
     */
    private void pushIfEnabled(List<Path> written) {
        AistioIntegrationProperties.WorkflowExportConfig cfg = props.getWorkflow();
        log.info("pushIfEnabled: pushEnabled={} tokenBlank={} controlPlaneUrl={} defs={}",
                cfg.isPushEnabled(), (cfg.getPushToken() == null || cfg.getPushToken().isBlank()),
                props.getControlPlaneUrl(), written.size());
        if (!cfg.isPushEnabled() || cfg.getPushToken() == null || cfg.getPushToken().isBlank()) {
            return;
        }
        if (props.getControlPlaneUrl() == null || props.getControlPlaneUrl().isBlank()) {
            log.warn("yunxi.aistio.control-plane-url 未配置，跳过编排图自动推送");
            return;
        }
        List<Path> definitions = written.stream()
                .filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(DEFINITION_SUFFIX))
                .toList();
        if (definitions.isEmpty()) {
            return;
        }
        new Thread(() -> {
            for (Path defPath : definitions) {
                pushOne(defPath, cfg);
            }
        }, "aistio-workflow-push").start();
    }

    private void pushOne(Path defPath, AistioIntegrationProperties.WorkflowExportConfig cfg) {
        try {
            Object draftSpec = objectMapper.readValue(defPath.toFile(), Object.class);
            String name = defPath.getFileName().toString().replace(DEFINITION_SUFFIX, "");
            // 推送体由 AistioControlPlaneClient 统一封装（tenant / namespace / name / draftSpec），
            // 凭据 PAT 仅作参数传入；失败重试亦在客户端内完成，此处只兜底记录异常，不阻塞启动。
            client.pushOrchestrationDefinition(props.getTenant(), resolvePushNamespace(cfg), name,
                    draftSpec, cfg.getPushToken());
        } catch (Exception e) {
            log.error("编排图推送异常: {}", defPath, e);
        }
    }

    private static String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 解析推送目标命名空间：优先返回显式配置的 {@code pushNamespace}；为空则回退到
     * {@code default} 命名空间（控制面 global 命名空间，PAT 拥有者通常具备 developer/operator 写权限）。
     * 控制面的个人命名空间形如 {@code personal-<hash>}（非 {@code ns-<userId>}），无法从 PAT 的 JWT 声明
     * 直接推导，若需推送到个人命名空间，运维须显式配置 {@code pushNamespace}。
     */
    private String resolvePushNamespace(AistioIntegrationProperties.WorkflowExportConfig cfg) {
        if (cfg.getPushNamespace() != null && !cfg.getPushNamespace().isBlank()) {
            return cfg.getPushNamespace();
        }
        return "default";
    }
}
