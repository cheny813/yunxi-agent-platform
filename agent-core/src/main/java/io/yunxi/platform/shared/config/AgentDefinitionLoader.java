package io.yunxi.platform.shared.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Agent 定义加载器 — 从 agent-definitions 目录加载 YAML 配置
 * <p>
 * 扫描 classpath:agent-definitions/ 目录下的所有 .yml 文件，
 * 反序列化为 {@link AgentDefinition} 配置模型并校验后缓存。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AgentDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLoader.class);

    @Value("${agentscope.config.path:classpath:agent-definitions/*.yml}")
    private String configPathPattern;

    private final List<AgentDefinition> agentDefinitions = new ArrayList<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 容器启动后扫描并加载全部 Agent 定义配置。
     * <p>按主配置路径模式与 agent-definitions 目录下的 .yml 文件解析资源，
     * 逐个反序列化为 {@link AgentDefinition} 并校验后缓存；IO 异常时记录错误日志。</p>
     */
    @PostConstruct
    public void loadConfigs() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            LinkedHashSet<Resource> allResources = new LinkedHashSet<>();
            addResources(resolver, allResources, configPathPattern);
            addResources(resolver, allResources, "classpath:agent-definitions/**/*.yml");

            log.info("正在加载 Agent 配置文件，找到 {} 个文件", allResources.size());

            for (Resource resource : allResources) {
                if (resource.exists()) {
                    loadAgentDefinition(resource);
                }
            }

            log.info("成功加载 {} 个 Agent 定义", agentDefinitions.size());
        } catch (IOException e) {
            log.error("加载 Agent 配置文件失败", e);
        }
    }

    /**
     * 按给定路径模式解析资源并去重加入集合。
     *
     * @param resolver Spring 资源解析器
     * @param set      去重后的资源集合（LinkedHashSet 保证顺序且唯一）
     * @param pattern  资源匹配模式（如 classpath:agent-definitions/*.yml）
     * @throws IOException 资源解析失败时抛出
     */
    private void addResources(PathMatchingResourcePatternResolver resolver,
            LinkedHashSet<Resource> set, String pattern) throws IOException {
        for (Resource r : resolver.getResources(pattern)) {
            set.add(r);
        }
    }

    /**
     * 解析单个 YAML 资源为 {@link AgentDefinition}。
     *
     * <p>依次执行根键校验、启用开关判断、字段校验，全部通过后加入已加载列表；
     * 任意环节不通过则记录日志并跳过该文件。</p>
     *
     * @param resource 待加载的 YAML 资源
     */
    private void loadAgentDefinition(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            AgentDefinitionWrapper wrapper = yamlMapper.readValue(inputStream, AgentDefinitionWrapper.class);
            if (wrapper == null || wrapper.getAgent() == null) {
                log.warn("跳过无效配置文件: {}（未找到 agent: 根键）", resource.getFilename());
                return;
            }
            AgentDefinition def = wrapper.getAgent();
            if (!def.isEnabled()) {
                log.info("跳过禁用的 Agent 配置: {}", resource.getFilename());
                return;
            }
            // 校验必要字段
            List<String> errors = validateDefinition(def, resource.getFilename());
            if (!errors.isEmpty()) {
                for (String err : errors) {
                    log.error("配置错误 [{}]: {}", resource.getFilename(), err);
                }
                log.warn("跳过无效配置文件: {}（校验不通过）", resource.getFilename());
                return;
            }
            agentDefinitions.add(def);
            log.info("加载 Agent: {} ({})", def.getName(), def.getDescription());
        } catch (IOException e) {
            log.error("解析配置文件失败 [{}]: YAML 格式错误或字段不匹配", resource.getFilename(), e);
        }
    }

    /**
     * 校验 Agent 定义的必要字段和逻辑正确性
     */
    private List<String> validateDefinition(AgentDefinition def, String filename) {
        List<String> errors = new java.util.ArrayList<>();
        if (def.getName() == null || def.getName().isBlank()) {
            errors.add("缺少必填字段: name");
        }
        if (def.getPrompt() == null || def.getPrompt().isBlank()) {
            errors.add("缺少必填字段: prompt");
        }
        // 校验编排模式配置
        if (def.getOrchestration() != null) {
            String pattern = def.getOrchestration().getPattern();
            if ("supervisor".equals(pattern)) {
                if (def.getOrchestration().getExperts() == null
                        || def.getOrchestration().getExperts().isEmpty()) {
                    errors.add("supervisor 模式必须配置 experts 列表");
                }
            } else if ("pipeline".equals(pattern)) {
                if (def.getOrchestration().getStages() == null
                        || def.getOrchestration().getStages().isEmpty()) {
                    errors.add("pipeline 模式必须配置 stages 列表");
                }
            } else if ("routing".equals(pattern)) {
                if (def.getOrchestration().getExperts() == null
                        || def.getOrchestration().getExperts().isEmpty()) {
                    errors.add("routing 模式必须配置 experts 列表");
                }
            } else if (!"single".equals(pattern)) {
                errors.add("不支持的编排模式: " + pattern + "（可选: single/supervisor/pipeline/routing）");
            }
        }
        return errors;
    }

    /**
     * 返回已加载的全部 Agent 定义（副本，避免外部修改内部列表）。
     *
     * @return Agent 定义列表
     */
    public List<AgentDefinition> getAgentDefinitions() {
        return new ArrayList<>(agentDefinitions);
    }

    /**
     * 按名称查找 Agent 定义。
     *
     * @param name Agent 名称
     * @return 匹配的定义，不存在时返回 null
     */
    public AgentDefinition getAgentDefinition(String name) {
        return agentDefinitions.stream()
                .filter(def -> def.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * YAML 反序列化根包装类，承载 {@code agent:} 根键下的 Agent 定义。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentDefinitionWrapper {
        private AgentDefinition agent;

        public AgentDefinition getAgent() {
            return agent;
        }

        public void setAgent(AgentDefinition agent) {
            this.agent = agent;
        }
    }
}