package io.yunxi.runtime;

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
 * <p>扫描 classpath 下 agent-definitions 目录中的 *.yml 定义文件，经 {@link AgentSpecYamlReader}
 * 严格解析为 {@link AgentSpec}（定义平面唯一真相源），复用 {@link AgentSpecValidator} 做结构校验
 * （{@code identity.agentId} / {@code identity.name} 必填）。缺 {@code identity} 的游离文件
 * （多为临时/误放文件）跳过并记 WARN；其余解析或校验不通过的跳过并记严重日志，均不阻断其余定义加载。
 * 字段名拼错不再被静默忽略，而是解析失败并给出建议字段名（见 {@link AgentSpecYamlReader}）。
 *
 * <p><b>与配置中心的同步语义是「比对后覆盖」（compare-then-upsert），而非"已存在就跳过"</b>：
 * <ul>
 *   <li>发布包是这类定义（随发布包交付、含权限模板与工具白名单）的所有权方，代码是最终裁决方——
 *       内容不一致时必须覆盖，否则升级发布包后提示词 / 权限 / 工具边界的修改<b>静默不生效</b>
 *       （部署成功但行为没变，最难察觉的一类失效）；</li>
 *   <li>内容一致时零写入——多实例滚动重启若每个实例都发布一次全量快照，会反复触发配置中心监听与
 *       热加载，形成无意义的重建风暴。</li>
 * </ul>
 * 只在配置中心<b>缺失</b>该 agentId 时导入；存在但不属于发布包管辖的 agentId 一律不动（当前无生产写入方，
 * 该约束确保管理端经配置中心写入的定义不会被覆盖）；同名冲突以发布包为准（见 {@code docs/guide/07-development.md}）。
 *
 * <p>加载后的定义存于 {@link ConfigCenter}（全平台统一 Agent 定义真相源，支持运行时热加载与多实例共享），
 * 由应用平面 {@code AgentProvisioner}（依赖本组件以确保先加载）在启动期桥接为运行时装配并注册进框架协议层 registry。</p>
 */
public class AgentDefinitionLoader {

    private static final Logger log = Logger.getLogger(AgentDefinitionLoader.class.getName());
    private static final String LOCATION = "classpath*:agent-definitions/**/*.yml";

    private final ConfigCenter configCenter;
    private final AgentSpecValidator specValidator;

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
                AgentSpec spec = AgentSpecYamlReader.read(resource.getInputStream());
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
                syncToConfigCenter(spec);
            } catch (Exception e) {
                log.severe("解析 AgentSpec 失败 " + label + ": " + e.getMessage());
            }
        }
    }

    /** 返回配置中心已登记的全部 AgentSpec（定义平面运行时真相源）。 */
    public Collection<AgentSpec> loadAllSpecs() {
        return configCenter.listAgentSpecs();
    }

    /**
     * 把一份发布时定义同步到配置中心：缺失则导入，内容有差异则覆盖，内容一致则零写入。
     *
     * <p>比对用 {@link AgentSpec} 的结构相等（全字段逐层值比较），只影响该 agentId，不做整表重写。
     * 覆盖属预期行为（发布包是所有权方），因此记 info 而非 warn；但措辞明确写出"代码为准"，
     * 运维若曾在配置中心手改过该定义，可从日志看出改动被回滚的原因。
     */
    private void syncToConfigCenter(AgentSpec spec) {
        String agentId = spec.identity().agentId();
        if (!configCenter.containsAgentSpec(agentId)) {
            configCenter.registerAgentSpec(spec);
            log.info("已导入发布时定义到配置中心: " + agentId);
            return;
        }
        if (spec.equals(configCenter.getAgentSpec(agentId))) {
            log.fine("发布时定义与配置中心内容一致，跳过写入: " + agentId);
            return;
        }
        configCenter.registerAgentSpec(spec);
        log.info("发布时定义已变更，按发布包覆盖配置中心同名定义（以代码为准）: " + agentId);
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
