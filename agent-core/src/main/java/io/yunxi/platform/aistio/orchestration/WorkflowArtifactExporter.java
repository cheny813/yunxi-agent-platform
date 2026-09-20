package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.GraphConfig;
import io.yunxi.platform.shared.config.MountBodyConfig;
import io.yunxi.platform.shared.config.MountConfig;
import io.yunxi.platform.shared.config.OrchestrationConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 */
@Service
public class WorkflowArtifactExporter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowArtifactExporter.class);

    private static final int MAX_PUSH_ATTEMPTS = 3;
    private static final long PUSH_RETRY_DELAY_MS = 10_000L;
    private static final String DEFINITION_SUFFIX = ".definition.json";

    private final AgentDefinitionLoader loader;
    private final AistioIntegrationProperties props;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public WorkflowArtifactExporter(AgentDefinitionLoader loader, AistioIntegrationProperties props,
            ObjectMapper objectMapper) {
        this.loader = loader;
        this.props = props;
        this.objectMapper = objectMapper;
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
        String url = props.getControlPlaneUrl().replaceAll("/+$", "") + "/api/v1/orchestration-definitions";
        List<Path> definitions = written.stream()
                .filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(DEFINITION_SUFFIX))
                .toList();
        if (definitions.isEmpty()) {
            return;
        }
        new Thread(() -> {
            for (Path defPath : definitions) {
                pushOne(url, defPath, cfg);
            }
        }, "aistio-workflow-push").start();
    }

    private void pushOne(String url, Path defPath, AistioIntegrationProperties.WorkflowExportConfig cfg) {
        try {
            Object draftSpec = objectMapper.readValue(defPath.toFile(), Object.class);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenant", props.getTenant());
            // namespace 是端点的必填项：createOrchestrationDefinition 在 body 绑定后即校验 namespace 非空，
            // 协作身份中间件不会回填请求体，故推送体必须显式携带。优先使用显式配置的 pushNamespace；
            // 否则回退到 default 命名空间（控制面 global 命名空间，PAT 拥有者通常具备 developer/operator 写权限）。
            // 注意：控制面的个人命名空间形如 personal-<hash>（非 ns-<userId>），无法从 PAT 的 JWT 声明直接推导，
            // 若需推送到个人命名空间，运维须显式配置 pushNamespace。
            body.put("namespace", resolvePushNamespace(cfg));
            String name = defPath.getFileName().toString()
                    .replace(DEFINITION_SUFFIX, "");
            body.put("name", name);
            body.put("draftSpec", draftSpec);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getPushToken());

            for (int attempt = 1; attempt <= MAX_PUSH_ATTEMPTS; attempt++) {
                try {
                    ResponseEntity<String> resp =
                            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        log.info("已自动推送编排图到 aistio 控制面: {} (name={})", defPath, name);
                        return;
                    }
                    log.warn("编排图推送返回非成功状态码 {}: {} (name={})", resp.getStatusCode(), defPath, name);
                } catch (Exception e) {
                    log.warn("编排图推送失败（第 {} 次）: {} (name={})", attempt, e.getMessage(), name);
                }
                if (attempt < MAX_PUSH_ATTEMPTS) {
                    Thread.sleep(PUSH_RETRY_DELAY_MS);
                }
            }
            log.warn("编排图推送在 {} 次重试后仍失败，可稍后在 aistio 控制台手工导入：{}", MAX_PUSH_ATTEMPTS, defPath);
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
