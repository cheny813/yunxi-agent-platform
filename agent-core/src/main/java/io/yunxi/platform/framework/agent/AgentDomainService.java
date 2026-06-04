package io.yunxi.platform.framework.agent;

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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.yunxi.platform.framework.model.ModelFactory;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.AgentConfigDto;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.exception.NotFoundException;

/**
 * Agent 领域服务
 *
 * <p>
 * 【框架层】负责 Agent 的生命周期管理。
 * </p>
 * <p>
 * <b>职责范围</b>：
 * <ul>
 * <li>注册 Agent 原型 Bean（prototype 作用域，每次获取新实例）</li>
 * <li>查询 Agent 信息</li>
 * <li>删除 Agent 定义</li>
 * <li>管理 Agent 元数据缓存</li>
 * </ul>
 * </p>
 * <p>
 * Agent 实例不再缓存到 ConcurrentHashMap，改为通过 DefaultListableBeanFactory
 * 注册 prototype Bean。每次调用 getAgentInstance() 都返回新实例，保证线程安全。
 * 多请求不再共享同一个 Agent 上下文。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.3.0
 */
@Service
public class AgentDomainService {

    private static final Logger log = LoggerFactory.getLogger(AgentDomainService.class);

    /** Agent 配置属性 */
    private final AgentscopeCoreProperties properties;

    /** 模型工厂 — 创建框架 Model 实例 */
    private final ModelFactory modelFactory;

    /** Spring BeanFactory — 用于注册/获取 prototype Agent Bean */
    private final DefaultListableBeanFactory beanFactory;

    /** Agent 信息缓存（key 为 Agent 名称） */
    private final Map<String, AgentInfoDto> agentCache = new ConcurrentHashMap<>();

    /** Agent 模型缓存（name → Model），供 AdvancedAgentFactory 使用 */
    private final Map<String, Model> modelCache = new ConcurrentHashMap<>();

    /** Agent 系统提示词缓存（name → sysPrompt） */
    private final Map<String, String> sysPromptCache = new ConcurrentHashMap<>();

    /** Agent 结构化输出 schema 缓存 */
    private final Map<String, String> agentSchemaCache = new ConcurrentHashMap<>();

    /** Agent 默认 RAG 模式缓存（name → ragMode） */
    private final Map<String, String> agentRagModeCache = new ConcurrentHashMap<>();

    /**
     * 构造 Agent 领域服务
     *
     * @param properties   Agent 配置属性
     * @param modelFactory 模型工厂
     * @param beanFactory  Spring BeanFactory
     */
    public AgentDomainService(AgentscopeCoreProperties properties,
            ModelFactory modelFactory,
            BeanFactory beanFactory) {
        this.properties = properties;
        this.modelFactory = modelFactory;
        this.beanFactory = (DefaultListableBeanFactory) beanFactory;
    }

    /**
     * 获取全部 Agent 列表
     *
     * @return Agent 列表
     */
    public List<AgentInfoDto> listAgents() {
        return agentCache.values().stream().toList();
    }

    /**
     * 获取指定 Agent 信息
     *
     * @param name Agent 名称
     * @return Agent 信息
     */
    public AgentInfoDto getAgent(String name) {
        AgentInfoDto info = agentCache.get(name);
        if (info == null) {
            throw new NotFoundException("Agent not found: " + name);
        }
        return info;
    }

    /**
     * 创建或覆盖 Agent（支持模型提供商配置）
     *
     * @param name   Agent 名称
     * @param config Agent 配置（可为空）
     * @return Agent 信息
     */
    public AgentInfoDto createAgent(String name, AgentConfigDto config) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Agent name 不能为空");
        }

        // 转换 AgentConfigDto → AgentModelConfig，再用 ModelFactory 创建框架 Model
        Model model = modelFactory.create(toModelConfig(config));

        String prompt = config != null && config.getPrompt() != null && !config.getPrompt().isBlank()
                ? config.getPrompt()
                : properties.getDefaultPrompt();

        String modelName = config != null && config.getModelName() != null && !config.getModelName().isBlank()
                ? config.getModelName()
                : properties.getModelName();

        // 注册 prototype Agent Bean（每次获取新实例）
        registerPrototypeAgentBean(name, model, prompt, properties.getWorkspaceBasePath() + "/" + name);

        AgentInfoDto info = new AgentInfoDto(name, prompt, modelName, Instant.now());
        agentCache.put(name, info);
        modelCache.put(name, model);
        sysPromptCache.put(name, prompt);
        return info;
    }

    /**
     * 注册 prototype 作用域的 Agent Bean
     * <p>
     * 每次从 BeanFactory 获取时创建新实例，避免多请求共享同一 Agent。
     * </p>
     */
    private void registerPrototypeAgentBean(String name, Model model,
            String prompt, String workspacePath) {
        if (beanFactory.containsBean(name + "-agent")) {
            beanFactory.destroySingleton(name + "-agent");
            beanFactory.removeBeanDefinition(name + "-agent");
        }

        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(Agent.class, () -> {
                    ReActAgent delegate = ReActAgent.builder()
                            .name(name)
                            .sysPrompt(prompt)
                            .model(model)
                            .build();
                    return HarnessAgent.from(delegate)
                            .workspace(workspacePath)
                            .compaction(buildCompactionConfig())
                            .build();
                })
                .setScope(BeanDefinition.SCOPE_PROTOTYPE);

        beanFactory.registerBeanDefinition(name + "-agent", builder.getBeanDefinition());
        log.info("注册 prototype Agent Bean: {}", name);
    }

    /**
     * 创建用户级别的 Agent 实例（指定 workspace 路径）
     * <p>
     * 与 {@link #createAgent} 的区别：
     * <ul>
     * <li>workspace 路径由调用方传入，而非使用默认的 {workspaceBasePath}/{name}</li>
     * <li>name 一般是 compositeKey = "agentName#userId"</li>
     * <li>不抛 BadRequestException（参数已在调用方验证）</li>
     * </ul>
     * </p>
     *
     * @param name          Agent 复合名称（如 "food-chat#zhangsan"）
     * @param config        Agent 配置
     * @param workspacePath workspace 路径（如
     *                      ".agentscope/workspace/users/zhangsan/food-chat/"）
     * @return Agent 信息
     */
    public AgentInfoDto createUserAgent(String name, AgentConfigDto config, String workspacePath) {
        // 使用 ModelFactory 创建框架 Model（替代原自建 createProvider）
        Model model = modelFactory.create(toModelConfig(config));

        String prompt = config != null && config.getPrompt() != null && !config.getPrompt().isBlank()
                ? config.getPrompt()
                : properties.getDefaultPrompt();

        String modelName = config != null && config.getModelName() != null && !config.getModelName().isBlank()
                ? config.getModelName()
                : properties.getModelName();

        registerPrototypeAgentBean(name, model, prompt, workspacePath);

        AgentInfoDto info = new AgentInfoDto(name, prompt, modelName, Instant.now());
        agentCache.put(name, info);
        modelCache.put(name, model);
        sysPromptCache.put(name, prompt);
        log.info("创建用户 Agent: {} (workspace={})", name, workspacePath);
        return info;
    }

    /**
     * 删除指定 Agent
     *
     * @param name Agent 名称
     */
    public void deleteAgent(String name) {
        if (agentCache.remove(name) == null) {
            throw new NotFoundException("Agent not found: " + name);
        }
        String beanName = name + "-agent";
        if (beanFactory.containsBean(beanName)) {
            beanFactory.destroySingleton(beanName);
        }
        log.info("删除 Agent: {}", name);
    }

    /**
     * 查找 Agent 实例（返回 null 如果不存在）
     *
     * @param name Agent 名称
     * @return Agent 实例，如果不存在返回 null
     */
    public Agent findAgent(String name) {
        try {
            return getAgentInstance(name);
        } catch (NotFoundException e) {
            return null;
        }
    }

    /**
     * 获取 Agent 实例（抛出异常如果不存在）
     * <p>
     * 每次调用返回 prototype 作用域的新实例，保证线程安全。
     * </p>
     *
     * @param name Agent 名称
     * @return Agent 实例
     * @throws NotFoundException 如果 Agent 不存在
     */
    public Agent getAgentInstance(String name) {
        String beanName = name + "-agent";
        if (!beanFactory.containsBean(beanName)) {
            throw new NotFoundException("Agent not found: " + name);
        }
        Agent agent = beanFactory.getBean(beanName, Agent.class);
        if (agent == null) {
            throw new NotFoundException("Agent not found: " + name);
        }
        return agent;
    }

    /**
     * 获取 Agent 的系统提示词
     */
    public String getAgentSysPrompt(String name) {
        return sysPromptCache.get(name);
    }

    /**
     * 获取 Agent 的模型实例
     */
    public Model getAgentModel(String name) {
        return modelCache.get(name);
    }

    /**
     * 获取当前 Agent 数量
     */
    public int countAgents() {
        return agentCache.size();
    }

    /**
     * 注册 Agent 信息
     */
    public void registerAgentInfoDto(String name, String description, String prompt, String modelName) {
        AgentInfoDto info = new AgentInfoDto(name, description, prompt, modelName, Instant.now());
        agentCache.put(name, info);
    }

    /**
     * 注册 Agent 实例（通过 prototype Bean 定义）
     */
    public void registerAgentInstance(String name, Agent agent) {
        if (name == null || name.isBlank() || agent == null) {
            return;
        }
        // 注册为 prototype Bean，但使用预先创建的实例作为 factory-method
        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(Agent.class, () -> agent)
                .setScope(BeanDefinition.SCOPE_PROTOTYPE);

        String beanName = name + "-agent";
        if (beanFactory.containsBean(beanName)) {
            beanFactory.removeBeanDefinition(beanName);
        }
        beanFactory.registerBeanDefinition(beanName, builder.getBeanDefinition());
        log.debug("注册 prototype Agent Bean: {}", name);
    }

    /**
     * 注册 Agent 的结构化输出 schema
     */
    public void registerAgentSchema(String name, String schema) {
        if (name != null && !name.isBlank() && schema != null && !schema.isBlank()) {
            agentSchemaCache.put(name, schema);
            log.info("注册 Agent [{}] 的结构化输出 schema", name);
        }
    }

    /**
     * 获取 Agent 的结构化输出 schema
     */
    public String getAgentSchema(String name) {
        return agentSchemaCache.get(name);
    }

    /**
     * 检查 Agent 是否启用了结构化输出
     */
    public boolean hasStructuredOutput(String name) {
        return agentSchemaCache.containsKey(name);
    }

    /**
     * 注册 Agent 的默认 RAG 模式
     */
    public void registerAgentRagMode(String name, String ragMode) {
        if (name != null && !name.isBlank()) {
            agentRagModeCache.put(name,
                    ragMode != null ? ragMode : io.yunxi.platform.shared.constants.ConfigDefaults.DEFAULT_RAG_MODE);
        }
    }

    /**
     * 获取 Agent 的默认 RAG 模式
     */
    public String getAgentRagMode(String name) {
        return agentRagModeCache.getOrDefault(name, io.yunxi.platform.shared.constants.ConfigDefaults.DEFAULT_RAG_MODE);
    }

    /**
     * 从配置构建 CompactionConfig
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
     * 将 AgentConfigDto 转换为 AgentModelConfig，供 ModelFactory 使用
     */
    private io.yunxi.platform.shared.config.AgentModelConfig toModelConfig(AgentConfigDto dto) {
        if (dto == null) {
            return null;
        }
        io.yunxi.platform.shared.config.AgentModelConfig config =
                new io.yunxi.platform.shared.config.AgentModelConfig();
        config.setProvider(dto.getProvider());
        config.setApiKey(dto.getApiKey());
        config.setModelName(dto.getModelName());
        config.setTemperature(dto.getTemperature());
        config.setMaxTokens(dto.getMaxTokens());
        return config;
    }
}
