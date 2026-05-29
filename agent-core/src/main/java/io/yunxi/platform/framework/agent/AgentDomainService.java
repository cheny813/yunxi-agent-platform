package io.yunxi.platform.framework.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.yunxi.platform.framework.embedding.ChatModelProvider;
import io.yunxi.platform.framework.embedding.ModelConfig;
import io.yunxi.platform.framework.embedding.ModelProviderFactory;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.AgentConfigDto;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.exception.NotFoundException;

/**
 * Agent 领域服务
 *
 * <p>
 * 【框架层】负责 Agent 的生命周期管理（单一职责）
 * </p>
 * <p>
 * <b>职责范围</b>：
 * <ul>
 * <li>创建 Agent 实例（通过 HarnessAgent 包装 ReActAgent）</li>
 * <li>查询 Agent 信息</li>
 * <li>删除 Agent 实例</li>
 * <li>管理 Agent 缓存</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.1.0
 */
@Service
public class AgentDomainService {

    private static final Logger log = LoggerFactory.getLogger(AgentDomainService.class);

    /**
     * Agent 配置属性
     */
    private final AgentscopeCoreProperties properties;

    /**
     * 模型工厂
     */
    private final ModelProviderFactory modelFactory;

    /**
     * Agent 信息缓存（key 为 Agent 名称）
     */
    private final Map<String, AgentInfoDto> agentCache = new ConcurrentHashMap<>();

    /**
     * Agent 实例缓存（key 为 Agent 名称），统一使用 Agent 接口
     */
    private final Map<String, Agent> agentInstanceCache = new ConcurrentHashMap<>();

    /**
     * Agent schema 缓存（用于结构化输出）
     */
    private final Map<String, String> agentSchemaCache = new ConcurrentHashMap<>();

    /**
     * Agent 默认 RAG 模式缓存（name → ragMode）
     */
    private final Map<String, String> agentRagModeCache = new ConcurrentHashMap<>();

    /**
     * Agent 模型提供商缓存（name → ChatModelProvider），供 AdvancedAgentFactory 使用
     */
    private final Map<String, ChatModelProvider> modelProviderCache = new ConcurrentHashMap<>();

    /**
     * Agent 系统提示词缓存（name → sysPrompt）
     */
    private final Map<String, String> sysPromptCache = new ConcurrentHashMap<>();

    /**
     * 构造 Agent 领域服务
     *
     * @param properties   Agent 配置属性
     * @param modelFactory 模型工厂
     */
    public AgentDomainService(AgentscopeCoreProperties properties, ModelProviderFactory modelFactory) {
        this.properties = properties;
        this.modelFactory = modelFactory;
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
        String apiKey = config != null && config.getApiKey() != null && !config.getApiKey().isBlank()
                ? config.getApiKey()
                : properties.getApiKey();

        // 安全日志：验证 API Key（屏蔽敏感信息）
        log.debug("API Key 配置验证完成，长度: {}", apiKey != null ? apiKey.length() : 0);

        if (apiKey == null || apiKey.isBlank()) {
            throw new BadRequestException("API Key 未配置，请在 application.yml 中设置 agentscope.api-key");
        }

        String modelName = config != null && config.getModelName() != null && !config.getModelName().isBlank()
                ? config.getModelName()
                : properties.getModelName();

        String prompt = config != null && config.getPrompt() != null && !config.getPrompt().isBlank()
                ? config.getPrompt()
                : properties.getDefaultPrompt();

        // 创建模型配置（默认使用 dashscope）
        String provider = config != null && config.getProvider() != null && !config.getProvider().isBlank()
                ? config.getProvider()
                : "dashscope";

        ModelConfig modelConfig = new ModelConfig(provider, apiKey, modelName);
        if (config != null && config.getTemperature() != null) {
            modelConfig.setTemperature(config.getTemperature());
        }
        if (config != null && config.getMaxTokens() != null) {
            modelConfig.setMaxTokens(config.getMaxTokens());
        }

        // 创建模型提供商
        ChatModelProvider modelProvider = modelFactory.createProvider(modelConfig);

        // 先清除旧的 Agent 实例（如果存在），确保使用新配置
        agentInstanceCache.remove(name);

        // 创建 ReActAgent 后通过 HarnessAgent 包装
        ReActAgent delegate = ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(modelProvider)
                .build();

        Agent agent = HarnessAgent.from(delegate)
                .workspace(properties.getWorkspaceBasePath() + "/" + name)
                .compaction(buildCompactionConfig())
                .build();

        AgentInfoDto info = new AgentInfoDto(name, prompt, modelName, Instant.now());
        agentCache.put(name, info);
        agentInstanceCache.put(name, agent);
        modelProviderCache.put(name, modelProvider);
        sysPromptCache.put(name, prompt);
        return info;
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
        String apiKey = config != null && config.getApiKey() != null && !config.getApiKey().isBlank()
                ? config.getApiKey()
                : properties.getApiKey();

        String modelName = config != null && config.getModelName() != null && !config.getModelName().isBlank()
                ? config.getModelName()
                : properties.getModelName();

        String prompt = config != null && config.getPrompt() != null && !config.getPrompt().isBlank()
                ? config.getPrompt()
                : properties.getDefaultPrompt();

        String provider = config != null && config.getProvider() != null && !config.getProvider().isBlank()
                ? config.getProvider()
                : "dashscope";

        ModelConfig modelConfig = new ModelConfig(provider, apiKey, modelName);
        if (config != null && config.getTemperature() != null) {
            modelConfig.setTemperature(config.getTemperature());
        }
        if (config != null && config.getMaxTokens() != null) {
            modelConfig.setMaxTokens(config.getMaxTokens());
        }

        ChatModelProvider modelProvider = modelFactory.createProvider(modelConfig);

        // 清除旧的同名实例（如果存在）
        agentInstanceCache.remove(name);

        ReActAgent delegate = ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(modelProvider)
                .build();

        Agent agent = HarnessAgent.from(delegate)
                .workspace(workspacePath)
                .compaction(buildCompactionConfig())
                .build();

        AgentInfoDto info = new AgentInfoDto(name, prompt, modelName, Instant.now());
        agentCache.put(name, info);
        agentInstanceCache.put(name, agent);
        modelProviderCache.put(name, modelProvider);
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
        agentInstanceCache.remove(name);
        log.info("删除 Agent: {}", name);
    }

    /**
     * 查找 Agent 实例（返回 null 如果不存在）
     *
     * @param name Agent 名称
     * @return Agent 实例，如果不存在返回 null
     */
    public Agent findAgent(String name) {
        return agentInstanceCache.get(name);
    }

    /**
     * 获取 Agent 实例（抛出异常如果不存在）
     *
     * @param name Agent 名称
     * @return Agent 实例
     * @throws NotFoundException 如果 Agent 不存在
     */
    public Agent getAgentInstance(String name) {
        Agent agent = agentInstanceCache.get(name);
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
     * 获取 Agent 的模型提供商
     */
    public ChatModelProvider getAgentModelProvider(String name) {
        return modelProviderCache.get(name);
    }

    /**
     * 获取当前 Agent 数量
     */
    public int countAgents() {
        return agentInstanceCache.size();
    }

    /**
     * 注册 Agent 信息
     */
    public void registerAgentInfoDto(String name, String description, String prompt, String modelName) {
        AgentInfoDto info = new AgentInfoDto(name, description, prompt, modelName, Instant.now());
        agentCache.put(name, info);
    }

    /**
     * 注册 Agent 实例
     */
    public void registerAgentInstance(String name, Agent agent) {
        if (name != null && !name.isBlank() && agent != null) {
            agentInstanceCache.put(name, agent);
        }
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
}
