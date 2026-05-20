package io.yunxi.platform.framework.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.tool.Toolkit;
import io.yunxi.platform.framework.embedding.ModelConfig;
import io.yunxi.platform.framework.hook.TextToolCallParserHook;
import io.yunxi.platform.framework.plan.ReMePlanStorage;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.AdvancedAgentConfigDto.ModelConfigDto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Agent 构建助手
 *
 * <p>
 * 提取 AgentDomainService、AgentInitializationService、AdvancedAgentFactory
 * 中重复的 Agent 构建逻辑，统一维护。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AgentBuilderHelper {

    /** Studio 消息钩子（可选） */
    @Autowired
    private ObjectProvider<StudioMessageHook> studioMessageHookProvider;

    /** PlanNotebook 持久化存储（替换默认 InMemoryPlanStorage） */
    private final ReMePlanStorage reMePlanStorage;

    public AgentBuilderHelper(ReMePlanStorage reMePlanStorage) {
        this.reMePlanStorage = reMePlanStorage;
    }

    /**
     * 从 AgentDefinition 的 ModelConfig DTO 解析为 framework ModelConfig
     *
     * @param dtoModelConfig DTO 模型配置（可为 null）
     * @param properties     核心配置属性（提供默认值）
     * @return 解析后的 ModelConfig
     */
    public ModelConfig resolveModelConfig(ModelConfigDto dtoModelConfig,
            AgentscopeCoreProperties properties) {
        if (dtoModelConfig == null) {
            return new ModelConfig(
                    properties.getProvider(),
                    properties.getApiKey(),
                    properties.getModelName());
        }

        ModelConfig modelConfig = new ModelConfig(
                dtoModelConfig.getProvider() != null ? dtoModelConfig.getProvider() : properties.getProvider(),
                dtoModelConfig.getApiKey() != null ? dtoModelConfig.getApiKey() : properties.getApiKey(),
                dtoModelConfig.getModelName() != null ? dtoModelConfig.getModelName() : properties.getModelName());

        if (dtoModelConfig.getProjectId() != null) {
            modelConfig.setProjectId(dtoModelConfig.getProjectId());
        }
        if (dtoModelConfig.getBaseUrl() != null) {
            modelConfig.setBaseUrl(dtoModelConfig.getBaseUrl());
        }
        if (dtoModelConfig.getTemperature() != null) {
            modelConfig.setTemperature(dtoModelConfig.getTemperature());
        }
        if (dtoModelConfig.getMaxTokens() != null) {
            modelConfig.setMaxTokens(dtoModelConfig.getMaxTokens());
        }

        return modelConfig;
    }

    /**
     * 注入标准 Hook 集合（Studio + TextToolCallParser）
     *
     * @param builder Agent 构建器
     * @param toolkit 工具包（用于 TextToolCallParserHook）
     */
    public void injectStandardHooks(ReActAgent.Builder builder, Toolkit toolkit) {
        if (studioMessageHookProvider.getIfAvailable() != null) {
            builder.hook(studioMessageHookProvider.getIfAvailable());
        }
        TextToolCallParserHook textToolCallParserHook = new TextToolCallParserHook(toolkit);
        builder.hook(textToolCallParserHook);
    }

    /**
     * 仅注入 Studio Hook（不注入 TextToolCallParserHook）
     *
     * @param builder Agent 构建器
     */
    public void injectStudioHook(ReActAgent.Builder builder) {
        if (studioMessageHookProvider.getIfAvailable() != null) {
            builder.hook(studioMessageHookProvider.getIfAvailable());
        }
    }

    /**
     * 配置 PlanNotebook（任务自动规划 + 模板预创建 + 用户确认）
     * <p>
     * 从 AgentDefinition YAML 配置读取:
     * <ul>
     * <li>plan.enabled — 是否启用规划能力</li>
     * <li>plan.userConfirm — 规划是否需要用户确认</li>
     * <li>plan.maxSubtasks — 最大子任务数</li>
     * </ul>
     * 使用 ReMePlanStorage 替换默认的 InMemoryPlanStorage，
     * 使规划可持久化到记忆体系。
     * </p>
     */
    public void configurePlanNotebook(ReActAgent.Builder builder, AgentDefinition definition) {
        if (definition.getEnablePlanNotebook() != null && definition.getEnablePlanNotebook()) {
            var planConfig = definition.getPlan();
            PlanNotebook planNotebook = PlanNotebook.builder()
                    .storage(reMePlanStorage)
                    .maxSubtasks(definition.getPlanMaxSubtasks() != null ? definition.getPlanMaxSubtasks() : null)
                    .needUserConfirm(planConfig != null && planConfig.isUserConfirm())
                    .planToHint(new ChinesePlanToHint())
                    .build();
            builder.planNotebook(planNotebook);
        }
    }

    /**
     * 配置 MetaTool（LLM 动态切换工具组）
     *
     * @param builder    Agent 构建器
     * @param definition Agent 定义配置
     */
    public void configureMetaTool(ReActAgent.Builder builder, AgentDefinition definition) {
        if (definition.getEnableMetaTool() != null && definition.getEnableMetaTool()) {
            builder.enableMetaTool(true);
        }
    }

    /**
     * 配置结构化输出 schema 到模型配置
     *
     * @param modelConfig 模型配置
     * @param definition  Agent 定义配置
     */
    public void configureStructuredOutput(ModelConfig modelConfig, AgentDefinition definition) {
        if (definition.getStructuredOutput() != null && definition.getStructuredOutput().isEnabled()) {
            String schema = definition.getStructuredOutput().getSchema();
            if (schema != null && !schema.isBlank()) {
                modelConfig.setStructuredOutputSchema(schema);
            }
        }
    }
}
