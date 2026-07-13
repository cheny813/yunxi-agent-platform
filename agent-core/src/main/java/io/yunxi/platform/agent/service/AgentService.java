package io.yunxi.platform.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.AgentConfigDto;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.exception.NotFoundException;

/**
 * Agent 服务，负责 Agent 生命周期管理。
 *
 * <p>
 * 使用 Spring DefaultListableBeanFactory 注册 prototype 作用域的 Agent Bean。
 * 每次调用 getAgentInstance() 返回新实例，保证线程安全。
 * </p>
 *
 * <p>
 * 使用 HarnessAgent.builder() 构建 Agent。
 * </p>
 */
@Service
public class AgentService {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 核心配置属性 */
    private final AgentscopeCoreProperties properties;

    /** 模型工厂，用于创建 LLM 模型实例 */
    private final ModelFactory modelFactory;

    /** Spring Bean 工厂，用于动态注册和管理 Agent Bean */
    private final DefaultListableBeanFactory beanFactory;

    /** Agent 信息缓存，key 为 Agent 名称，value 为 Agent 元信息 */
    private final Map<String, AgentInfoDto> agentCache = new ConcurrentHashMap<>();

    /** Agent 模型缓存，key 为 Agent 名称，value 为其关联的 Model 实例 */
    private final Map<String, Model> modelCache = new ConcurrentHashMap<>();

    /** Agent 系统提示词缓存，key 为 Agent 名称，value 为系统提示词 */
    private final Map<String, String> sysPromptCache = new ConcurrentHashMap<>();

    /** Agent 结构化输出 Schema 缓存，key 为 Agent 名称，value 为 JSON Schema */
    private final Map<String, String> agentSchemaCache = new ConcurrentHashMap<>();

    /** Agent RAG 模式缓存，key 为 Agent 名称，value 为 RAG 模式标识 */
    private final Map<String, String> agentRagModeCache = new ConcurrentHashMap<>();

    /**
     * 构造 Agent 服务。
     *
     * @param properties   核心配置属性
     * @param modelFactory 模型工厂
     * @param beanFactory  Spring Bean 工厂
     */
    public AgentService(AgentscopeCoreProperties properties,
            ModelFactory modelFactory,
            BeanFactory beanFactory) {
        this.properties = properties;
        this.modelFactory = modelFactory;
        this.beanFactory = (DefaultListableBeanFactory) beanFactory;
    }

    /**
     * 获取所有已注册 Agent 的信息列表。
     *
     * @return Agent 信息列表
     */
    public List<AgentInfoDto> listAgents() {
        return agentCache.values().stream().toList();
    }

    /**
     * 获取指定 Agent 的信息。
     *
     * @param name Agent 名称
     * @return Agent 信息
     * @throws NotFoundException Agent 不存在时抛出
     */
    public AgentInfoDto getAgent(String name) {
        AgentInfoDto info = agentCache.get(name);
        if (info == null)
            throw new NotFoundException("Agent not found: " + name);
        return info;
    }

    /**
     * 创建 Agent 并注册到容器。
     *
     * <p>
     * 创建流程：
     * 1. 验证 Agent 名称
     * 2. 创建 LLM 模型
     * 3. 解析系统提示词和模型名称（使用配置默认值作为兜底）
     * 4. 注册 prototype 作用域的 Agent Bean
     * 5. 更新所有缓存
     * </p>
     *
     * @param name   Agent 名称
     * @param config Agent 配置
     * @return Agent 信息
     * @throws BadRequestException 名称为空时抛出
     */
    public AgentInfoDto createAgent(String name, AgentConfigDto config) {
        if (name == null || name.isBlank())
            throw new BadRequestException("Agent name 不能为空");
        // 创建模型
        Model model = modelFactory.create(toModelConfig(config));
        // 解析提示词，优先使用配置中的值，否则使用全局默认值
        String prompt = config != null && config.getPrompt() != null && !config.getPrompt().isBlank()
                ? config.getPrompt()
                : properties.getDefaultPrompt();
        // 解析模型名称
        String modelName = config != null && config.getModelName() != null && !config.getModelName().isBlank()
                ? config.getModelName()
                : properties.getModelName();
        // 注册 prototype Agent Bean
        registerPrototypeAgentBean(name, model, prompt, properties.getWorkspaceBasePath() + "/agents/" + name);
        // 更新缓存
        AgentInfoDto info = new AgentInfoDto(name, prompt, modelName, Instant.now());
        agentCache.put(name, info);
        modelCache.put(name, model);
        sysPromptCache.put(name, prompt);
        return info;
    }

    /**
     * 注册 prototype 作用域的 Agent Bean。
     *
     * <p>
     * 每次从容器获取时返回新的 Agent 实例，保证线程安全。
     * 如果同名 Bean 已存在，先销毁旧的再注册新的。
     * </p>
     *
     * @param name          Agent 名称
     * @param model         LLM 模型实例
     * @param prompt        系统提示词
     * @param workspacePath 工作空间路径
     */
    private void registerPrototypeAgentBean(String name, Model model, String prompt, String workspacePath) {
        // 如果同名 Bean 已存在，先销毁
        if (beanFactory.containsBean(name + "-agent")) {
            beanFactory.destroySingleton(name + "-agent");
            beanFactory.removeBeanDefinition(name + "-agent");
        }
        // 使用 BeanDefinitionBuilder 注册 prototype 作用域的 Agent
        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(Agent.class, () -> HarnessAgent.builder()
                        .name(name).sysPrompt(prompt).model(model)
                        .workspace(workspacePath).compaction(buildCompactionConfig())
                        .build())
                .setScope(BeanDefinition.SCOPE_PROTOTYPE);
        beanFactory.registerBeanDefinition(name + "-agent", builder.getBeanDefinition());
        log.info("注册 prototype Agent Bean: {}", name);
    }

    /**
     * 删除 Agent。
     *
     * <p>
     * 从缓存中移除 Agent 信息，并销毁 Spring 容器中的 Agent Bean。
     * </p>
     *
     * @param name Agent 名称
     * @throws NotFoundException Agent 不存在时抛出
     */
    public void deleteAgent(String name) {
        if (agentCache.remove(name) == null)
            throw new NotFoundException("Agent not found: " + name);
        String beanName = name + "-agent";
        if (beanFactory.containsBean(beanName)) {
            beanFactory.destroySingleton(beanName);
        }
        log.info("删除 Agent: {}", name);
    }

    /**
     * 查找 Agent 实例（不抛异常）。
     *
     * <p>
     * 与 getAgentInstance 不同，此方法在 Agent 不存在时返回 null 而非抛出异常。
     * </p>
     *
     * @param name Agent 名称
     * @return Agent 实例，不存在时返回 null
     */
    public Agent findAgent(String name) {
        try {
            return getAgentInstance(name);
        } catch (NotFoundException e) {
            return null;
        }
    }

    /**
     * 获取 Agent 实例。
     *
     * <p>
     * 从 Spring 容器中获取 prototype 作用域的 Agent Bean，
     * 每次调用返回新实例。
     * </p>
     *
     * @param name Agent 名称
     * @return Agent 实例
     * @throws NotFoundException Agent 不存在时抛出
     */
    public Agent getAgentInstance(String name) {
        String beanName = name + "-agent";
        if (!beanFactory.containsBean(beanName))
            throw new NotFoundException("Agent not found: " + name);
        Agent agent = beanFactory.getBean(beanName, Agent.class);
        if (agent == null)
            throw new NotFoundException("Agent not found: " + name);
        return agent;
    }

    /**
     * 获取 Agent 的系统提示词。
     *
     * @param name Agent 名称
     * @return 系统提示词，不存在时返回 null
     */
    public String getAgentSysPrompt(String name) {
        return sysPromptCache.get(name);
    }

    /**
     * 获取 Agent 关联的模型实例。
     *
     * @param name Agent 名称
     * @return Model 实例，不存在时返回 null
     */
    public Model getAgentModel(String name) {
        return modelCache.get(name);
    }

    /**
     * 获取已注册 Agent 的数量。
     *
     * @return Agent 数量
     */
    public int countAgents() {
        return agentCache.size();
    }

    /**
     * 注册 Agent 信息到缓存。
     *
     * <p>
     * 由 AgentConfigurer 在创建 Agent 后调用，将 Agent 元信息存入缓存。
     * </p>
     *
     * @param name        Agent 名称
     * @param description Agent 描述
     * @param prompt      系统提示词
     * @param modelName   模型名称
     */
    public void registerAgentInfoDto(String name, String description, String prompt, String modelName) {
        agentCache.put(name, new AgentInfoDto(name, description, prompt, modelName, Instant.now()));
    }

    /**
     * 注册已构建好的 Agent 实例到 Spring 容器。
     *
     * <p>
     * 将外部已构建完成的 Agent 实例注册为 prototype 作用域的 Bean，
     * 如果同名 Bean 已存在则先移除旧的 BeanDefinition。
     * </p>
     *
     * @param name  Agent 名称
     * @param agent 已构建的 Agent 实例
     */
    public void registerAgentInstance(String name, Agent agent) {
        if (name == null || name.isBlank() || agent == null)
            return;
        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(Agent.class, () -> agent)
                .setScope(BeanDefinition.SCOPE_PROTOTYPE);
        String beanName = name + "-agent";
        if (beanFactory.containsBean(beanName))
            beanFactory.removeBeanDefinition(beanName);
        beanFactory.registerBeanDefinition(beanName, builder.getBeanDefinition());
    }

    /**
     * 注册 Agent 的结构化输出 Schema。
     *
     * @param name   Agent 名称
     * @param schema JSON Schema 字符串
     */
    public void registerAgentSchema(String name, String schema) {
        if (name != null && !name.isBlank() && schema != null && !schema.isBlank()) {
            agentSchemaCache.put(name, schema);
        }
    }

    /**
     * 获取 Agent 的结构化输出 Schema。
     *
     * @param name Agent 名称
     * @return JSON Schema 字符串，不存在时返回 null
     */
    public String getAgentSchema(String name) {
        return agentSchemaCache.get(name);
    }

    /**
     * 判断 Agent 是否配置了结构化输出。
     *
     * @param name Agent 名称
     * @return true 表示已配置结构化输出 Schema
     */
    public boolean hasStructuredOutput(String name) {
        return agentSchemaCache.containsKey(name);
    }

    /**
     * 注册 Agent 的 RAG 模式。
     *
     * <p>
     * 如果未指定 ragMode，使用默认值 DEFAULT_RAG_MODE。
     * </p>
     *
     * @param name    Agent 名称
     * @param ragMode RAG 模式标识（如 NONE、GENERIC、AGENTIC）
     */
    public void registerAgentRagMode(String name, String ragMode) {
        if (name != null && !name.isBlank()) {
            agentRagModeCache.put(name, ragMode != null ? ragMode
                    : io.yunxi.platform.shared.constants.ConfigDefaults.DEFAULT_RAG_MODE);
        }
    }

    /**
     * 获取 Agent 的 RAG 模式。
     *
     * @param name Agent 名称
     * @return RAG 模式标识，不存在时返回默认值
     */
    public String getAgentRagMode(String name) {
        return agentRagModeCache.getOrDefault(name,
                io.yunxi.platform.shared.constants.ConfigDefaults.DEFAULT_RAG_MODE);
    }

    /**
     * 构建 Memory Compaction 配置。
     *
     * <p>
     * 从核心配置属性中读取 Compaction 参数，构建 CompactionConfig 实例。
     * </p>
     *
     * @return CompactionConfig 实例
     */
    private CompactionConfig buildCompactionConfig() {
        var c = properties.getCompaction();
        return CompactionConfig.builder()
                .triggerMessages(c.getTriggerMessages())
                .triggerTokens(c.getTriggerTokens())
                .keepMessages(c.getKeepMessages())
                .flushBeforeCompact(c.isFlushBeforeCompact())
                .offloadBeforeCompact(c.isOffloadBeforeCompact())
                .build();
    }

    /**
     * 将 AgentConfigDto 转换为 AgentModelConfig。
     *
     * <p>
     * DTO 到配置对象的转换，用于将外部请求参数转换为内部模型配置。
     * </p>
     *
     * @param dto Agent 配置 DTO
     * @return AgentModelConfig 实例，dto 为 null 时返回 null
     */
    private io.yunxi.platform.shared.config.AgentModelConfig toModelConfig(AgentConfigDto dto) {
        if (dto == null)
            return null;
        var config = new io.yunxi.platform.shared.config.AgentModelConfig();
        config.setProvider(dto.getProvider());
        config.setApiKey(dto.getApiKey());
        config.setModelName(dto.getModelName());
        config.setTemperature(dto.getTemperature());
        config.setMaxTokens(dto.getMaxTokens());
        return config;
    }
}
