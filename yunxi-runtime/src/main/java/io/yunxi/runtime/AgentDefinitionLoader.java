package io.yunxi.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.yunxi.config.ConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.AgentSpecValidator;
import java.io.IOException;
import java.util.Collection;
import java.util.logging.Logger;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Agent 定义加载器（定义平面入口，启动期执行）。
 *
 * <p>扫描 classpath 下 agent-definitions 目录中的 *.yml 定义文件，反序列化为 {@link AgentSpec}
 * （定义平面唯一真相源），复用 {@link AgentSpecValidator} 做结构校验（{@code identity.agentId} /
 * {@code identity.name} 必填）。缺 {@code identity} 的游离文件（多为临时/误放文件）跳过并记 WARN；
 * 其余结构校验不通过的跳过并记严重日志，均不阻断其余定义加载。
 * 加载的定义写入 {@link ConfigCenter}（全平台统一 Agent 定义真相源，支持运行时热加载与多实例共享），
 * 由应用平面 {@code AgentProvisioner}（依赖本组件以确保先加载）在启动期桥接为运行时装配并注册进框架协议层 registry。</p>
 */
public class AgentDefinitionLoader {

    private static final Logger log = Logger.getLogger(AgentDefinitionLoader.class.getName());
    private static final String LOCATION = "classpath*:agent-definitions/**/*.yml";

    private final ConfigCenter configCenter;
    private final AgentSpecValidator specValidator;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public AgentDefinitionLoader(ConfigCenter configCenter, AgentSpecValidator specValidator) {
        this.configCenter = configCenter;
        this.specValidator = specValidator;
    }

    @PostConstruct
    public void load() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(LOCATION);
        } catch (IOException e) {
            log.severe("扫描 AgentSpec 失败: " + e.getMessage());
            return;
        }
        for (Resource resource : resources) {
            String label = safeName(resource);
            try {
                AgentSpec spec = yamlMapper.readValue(resource.getInputStream(), AgentSpec.class);
                // 游离文件兜底：缺 identity 的 yml 不是合法 AgentSpec（多为临时/误放的游离文件），
                // 跳过并记 WARN，区别于真正的结构校验失败（仍记 severe 且不阻断其余加载）。
                if (spec.identity() == null) {
                    log.warning("跳过游离 AgentSpec（缺少 identity，疑似非 AgentSpec 文件）: " + label);
                    continue;
                }
                AgentSpec.ValidationResult result = specValidator.validate(spec);
                if (!result.valid()) {
                    log.severe("跳过 AgentSpec " + label + ": " + String.join("; ", result.errors()));
                    continue;
                }
                // classpath 定义作为配置中心种子：仅当配置中心尚未登记该 agentId 时导入，避免覆盖运行时热更新的定义。
                if (configCenter.containsAgentSpec(spec.identity().agentId())) {
                    log.info("配置中心已存在 AgentSpec，跳过 classpath 种子: " + spec.identity().agentId());
                    continue;
                }
                configCenter.registerAgentSpec(spec);
                log.info("已导入 AgentSpec 到配置中心: " + spec.identity().agentId());
            } catch (Exception e) {
                log.severe("解析 AgentSpec 失败 " + label + ": " + e.getMessage());
            }
        }
    }

    /** 返回配置中心已登记的全部 AgentSpec（定义平面运行时真相源）。 */
    public Collection<AgentSpec> loadAllSpecs() {
        return configCenter.listAgentSpecs();
    }

    private static String safeName(Resource resource) {
        try {
            String filename = resource.getFilename();
            return filename != null ? filename : resource.getDescription();
        } catch (Exception e) {
            return resource.getDescription();
        }
    }
}
