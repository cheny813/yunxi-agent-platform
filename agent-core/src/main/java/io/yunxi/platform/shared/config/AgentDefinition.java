package io.yunxi.platform.shared.config;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.yunxi.platform.shared.dto.StructuredOutputConfigDto;

/**
 * Agent 定义配置 — 从 YAML 配置文件加载的顶层 Agent 定义
 * <p>
 * 80% 的场景只需配置基础字段和编排模式，框架自动装配。
 * 15% 的场景通过 extensions 扩展点插入自定义逻辑。
 * 5% 的场景通过 AgentCustomizer 完全自定义。
 * </p>
 *
 * <pre>
 * 配置示例：
 * agent:
 *   name: "nutrition-assistant"
 *   description: "学校营养配餐助手"
 *   prompt: "你是一位专业的营养顾问..."
 *   model:
 *     provider: "openai"
 *     name: "gpt-4o"
 *   orchestration:
 *     pattern: "supervisor"
 *     experts:
 *       - name: "dish-searcher"
 *         description: "搜索菜品"
 * </pre>
 *
 * @author yunxi-agent-platform
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDefinition {

    // ========== 基本信息 ==========

    /** Agent 名称（唯一标识） */
    private String name;

    /** 显示名称 */
    private String displayName;

    /** 描述 */
    private String description;

    /** 是否启用 */
    private boolean enabled = true;

    // ========== Prompt ==========

    /** 系统提示词 */
    private String prompt;

    // ========== 编排模式 ==========

    /** 编排配置（取代旧的 mode 枚举和 ModeResolver） */
    private OrchestrationConfig orchestration;

    // ========== 模型配置 ==========

    /** 模型配置 */
    private AgentModelConfig model;

    // ========== 工具配置 ==========

    /** 工具配置 */
    private ToolConfig tools;

    /**
     * 工具组配置（系统内置组 + MCP 服务器组）
     *
     * <p>结构：
     * <pre>{@code
     * toolsGroup:
     *   systemToolsGroup: [memory, filesystem]     # 系统内置组
     *   mcpServersToolsGroup: [database]            # MCP 服务器组（也隐含加载同名 MCP 服务器）
     * }</pre>
     *
     * <p>不配置时默认仅 memory 组可见。
     * {@code ToolsGroupConfig} 的 JavaDoc 列出了所有系统内置组的可选值。
     */
    private ToolsGroupConfig toolsGroup;

    // ========== 记忆配置 ==========

    /** 记忆配置 */
    private MemoryConfig memory;

    // ========== 计划能力 ==========

    /** 计划配置（PlanNotebook） */
    private PlanConfig plan;

    // ========== 运行时参数 ==========

    /** 运行时配置 */
    private RuntimeConfig runtime;

    // ========== 扩展点 ==========

    /** 扩展点配置 */
    private ExtensionConfig extensions;

    // ========== 结构化输出 ==========

    /** 结构化输出配置 */
    private StructuredOutputConfigDto structuredOutput;

    // ========== Profile 多配置 ==========

    /** Profile 映射：name → ProfileDefinition */
    private Map<String, ProfileDefinition> profiles;

    /** 默认 Profile 名称 */
    private String defaultProfile;

    // ========== RAG 配置 ==========

    /** 默认 RAG 模式：NONE / GENERIC / AGENTIC（请求未指定时使用此默认值） */
    private String ragMode = io.yunxi.platform.shared.constants.ConfigDefaults.DEFAULT_RAG_MODE;

    // ========== Getter / Setter ==========

    public AgentDefinition() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public OrchestrationConfig getOrchestration() {
        return orchestration;
    }

    public void setOrchestration(OrchestrationConfig orchestration) {
        this.orchestration = orchestration;
    }

    public AgentModelConfig getModel() {
        return model;
    }

    public void setModel(AgentModelConfig model) {
        this.model = model;
    }

    public ToolConfig getTools() {
        return tools;
    }

    public void setTools(ToolConfig tools) {
        this.tools = tools;
    }

    public ToolsGroupConfig getToolsGroup() {
        return toolsGroup;
    }

    public void setToolsGroup(ToolsGroupConfig toolsGroup) {
        this.toolsGroup = toolsGroup;
    }

    public MemoryConfig getMemory() {
        return memory;
    }

    public void setMemory(MemoryConfig memory) {
        this.memory = memory;
    }

    public PlanConfig getPlan() {
        return plan;
    }

    public void setPlan(PlanConfig plan) {
        this.plan = plan;
    }

    public RuntimeConfig getRuntime() {
        return runtime;
    }

    public void setRuntime(RuntimeConfig runtime) {
        this.runtime = runtime;
    }

    public ExtensionConfig getExtensions() {
        return extensions;
    }

    public void setExtensions(ExtensionConfig extensions) {
        this.extensions = extensions;
    }

    public StructuredOutputConfigDto getStructuredOutput() {
        return structuredOutput;
    }

    public void setStructuredOutput(StructuredOutputConfigDto structuredOutput) {
        this.structuredOutput = structuredOutput;
    }

    // ========== 便捷委托方法 ==========

    /**
     * 是否启用 PlanNotebook（便捷方法，委托给 PlanConfig）
     */
    public Boolean getEnablePlanNotebook() {
        return plan != null ? plan.isEnabled() : null;
    }

    /**
     * PlanNotebook 最大子任务数量（便捷方法，委托给 PlanConfig）
     */
    public Integer getPlanMaxSubtasks() {
        return plan != null ? plan.getMaxSubtasks() : null;
    }

    /**
     * 是否启用 MetaTool（便捷方法，委托给 RuntimeConfig）
     */
    public Boolean getEnableMetaTool() {
        return runtime != null ? runtime.isEnableMetaTool() : null;
    }

    public Map<String, ProfileDefinition> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, ProfileDefinition> profiles) {
        this.profiles = profiles;
    }

    public String getDefaultProfile() {
        return defaultProfile;
    }

    public void setDefaultProfile(String defaultProfile) {
        this.defaultProfile = defaultProfile;
    }

    public String getRagMode() {
        return ragMode;
    }

    public void setRagMode(String ragMode) {
        this.ragMode = ragMode;
    }
}
