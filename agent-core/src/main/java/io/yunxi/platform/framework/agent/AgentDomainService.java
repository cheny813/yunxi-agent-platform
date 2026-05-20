package io.yunxi.platform.framework.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
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
 * <li>创建 Agent 实例（使用 AgentFactory）</li>
 * <li>查询 Agent 信息</li>
 * <li>删除 Agent 实例</li>
 * <li>管理 Agent 缓存</li>
 * </ul>
 * </p>
 * <p>
 * <b>层级说明</b>：
 * <ul>
 * <li>所属层级：框架层</li>
 * <li>单一职责：Agent 的创建、查询、删除</li>
 * <li>不负责：业务流程编排（由业务层负责）</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.0.0
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
     * Agent 实例缓存（key 为 Agent 名称）
     */
    private final Map<String, ReActAgent> agentInstanceCache = new ConcurrentHashMap<>();

    /**
     * Studio 消息钩子（可选）
     */
    @Autowired
    private AgentBuilderHelper builderHelper;

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
     * 创建或覆盖Agent（支持模型提供商配置）
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

        // 创建 ReActAgent - 使用 AgentScope SDK 原生 API
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(modelProvider);

        // 注入 Studio Hook（如果启用）
        builderHelper.injectStudioHook(builder);

        ReActAgent agent = builder.build();

        AgentInfoDto info = new AgentInfoDto(name, prompt, modelName, Instant.now());
        agentCache.put(name, info);
        agentInstanceCache.put(name, agent);
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

        // 同时删除实例缓存
        agentInstanceCache.remove(name);

        log.info("删除 Agent: {}", name);
    }

    /**
     * 获取 Agent 实例（返回 null 如果不存在）
     *
     * @param name Agent 名称
     * @return Agent 实例，如果不存在返回 null
     */
    public ReActAgent getReActAgent(String name) {
        return agentInstanceCache.get(name);
    }

    /**
     * 获取 Agent 实例（抛出异常如果不存在）
     *
     * @param name Agent 名称
     * @return Agent 实例
     * @throws NotFoundException 如果 Agent 不存在
     */
    public ReActAgent getAgentInstance(String name) {
        ReActAgent agent = agentInstanceCache.get(name);
        if (agent == null) {
            throw new NotFoundException("Agent not found: " + name);
        }
        return agent;
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
    public void registerAgentInstance(String name, ReActAgent agent) {
        if (name != null && !name.isBlank() && agent != null) {
            agentInstanceCache.put(name, agent);
        }
    }

    /** Agent schema 缓存（用于结构化输出） */
    private final Map<String, String> agentSchemaCache = new ConcurrentHashMap<>();

    /**
     * 注册 Agent 的结构化输出 schema
     *
     * @param name   Agent 名称
     * @param schema JSON Schema 字符串
     */
    public void registerAgentSchema(String name, String schema) {
        if (name != null && !name.isBlank() && schema != null && !schema.isBlank()) {
            agentSchemaCache.put(name, schema);
            log.info("注册 Agent [{}] 的结构化输出 schema", name);
        }
    }

    /**
     * 获取 Agent 的结构化输出 schema
     *
     * @param name Agent 名称
     * @return JSON Schema 字符串，如果未配置则返回 null
     */
    public String getAgentSchema(String name) {
        return agentSchemaCache.get(name);
    }

    /**
     * 检查 Agent 是否启用了结构化输出
     *
     * @param name Agent 名称
     * @return 如果启用了结构化输出则返回 true
     */
    public boolean hasStructuredOutput(String name) {
        return agentSchemaCache.containsKey(name);
    }

    /**
     * 获取 Agent 的 Toolkit 实例
     * <p>
     * 注意：此方法仅适用于在 Agent 创建时传入了 Toolkit 的情况。
     * 如果 Agent 在创建时未指定 Toolkit，则返回 null。
     * </p>
     *
     * @param name Agent 名称
     * @return Agent 的 Toolkit 实例，如果不存在或未设置则返回 null
     */
    public Toolkit getAgentToolkit(String name) {
        ReActAgent agent = agentInstanceCache.get(name);
        if (agent == null) {
            return null;
        }

        // 安全检查：确保不是在生产环境中滥用反射
        if (!isReflectionAllowed()) {
            log.warn("反射访问被禁止 - 安全检查失败: {}", name);
            return null;
        }

        try {
            // 通过反射获取 toolkit 字段（添加安全限制）
            java.lang.reflect.Field toolkitField = ReActAgent.class.getDeclaredField("toolkit");

            // 安全检查：验证字段访问权限
            if (!toolkitField.trySetAccessible()) {
                log.warn("无法获取访问权限: {}", name);
                return null;
            }

            Toolkit toolkit = (Toolkit) toolkitField.get(agent);

            // 安全检查：验证返回对象的安全性
            if (toolkit != null && !isValidToolkit(toolkit)) {
                log.warn("工具包安全检查失败: {}", name);
                return null;
            }

            return toolkit;
        } catch (NoSuchFieldException e) {
            log.warn("Agent [{}] 不存在 toolkit 字段", name);
            return null;
        } catch (IllegalAccessException e) {
            log.warn("安全异常：无法访问 Agent [{}] 的 toolkit 字段", name);
            return null;
        } catch (SecurityException e) {
            log.warn("安全管理器阻止访问 Agent [{}] toolkit: {}", name, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("获取 Agent [{}] toolkit 失败: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 检查是否允许使用反射访问
     * 
     * @return true 如果允许，false 如果禁止
     */
    private boolean isReflectionAllowed() {
        // 开发环境允许，生产环境禁止
        String profile = System.getProperty("spring.profiles.active", "dev");
        return "dev".equals(profile) || "test".equals(profile);
    }

    /**
     * 验证工具包对象的合法性
     * 
     * @param toolkit 要验证的工具包实例
     * @return true 如果合法，false 如果不合法
     */
    private boolean isValidToolkit(Toolkit toolkit) {
        // 基础安全检查：确保不是恶意代理对象
        if (toolkit == null) {
            return false;
        }

        // 验证类加载器：确保来自可信来源
        ClassLoader loader = toolkit.getClass().getClassLoader();
        if (loader == null || loader != Toolkit.class.getClassLoader()) {
            log.warn("可疑的工具包类加载器: {}", loader);
            return false;
        }

        return true;
    }
}
