package io.yunxi.platform.shared.config;

import java.util.List;

import io.yunxi.platform.shared.dto.AdvancedAgentConfigDto.ModelConfigDto;
import io.yunxi.platform.shared.dto.AdvancedAgentConfigDto.SkillConfig;

/**
 * Profile 定义 — 框架通用模型
 * <p>
 * 业务层通过 YAML 配置，为每个 Agent 定义多个 Profile。
 * 每个 Profile 可选择内置模式或完全自定义。
 * </p>
 *
 * <pre>
 * 配置优先级（从低到高）：
 *   1. Agent 级别配置（AgentDefinition）
 *   2. 自定义模式定义（AgentDefinition.modes 中的命名模式）
 *   3. Profile 级别配置（ProfileDefinition）
 * </pre>
 *
 * @author yunxi-agent-platform
 */
public class ProfileDefinition {

    /** Profile 名称（业务自定义，如 "chat", "recipe-make", "code-review"） */
    private String name;

    /** 展示名称（如 "营养咨询", "食谱生成"） */
    private String label;

    /** Profile 描述 */
    private String description;

    // ========== 模式选择 ==========

    /**
     * 模式名称
     * <p>
     * 可选值：
     * <ul>
     * <li>内置模式：chat / tool / expert / advanced</li>
     * <li>自定义模式：引用 AgentDefinition.modes 中定义的命名模式</li>
     * </ul>
     * 未设置时继承 Agent 级别的 mode。
     * </p>
     */
    private String mode;

    // ========== 以下字段覆盖 Agent 级别配置 ==========

    /** 专用 system prompt（覆盖 Agent 默认 prompt） */
    private String prompt;

    /** 追加到 Agent prompt 末尾（与 prompt 二选一，prompt 优先级更高） */
    private String promptSuffix;

    /** 激活的工具组列表 */
    private List<String> toolGroups;

    /** MCP 服务器列表 */
    private List<String> mcpServers;

    /** 最大迭代次数 */
    private Integer maxIters;

    /** 是否启用 PlanNotebook */
    private Boolean enablePlanNotebook;

    /** PlanNotebook 最大子任务数量 */
    private Integer planMaxSubtasks;

    /** PlanNotebook 自定义提示词 */
    private String planPrompt;

    /** 是否启用 Plan 可视化监控 */
    private Boolean enablePlanMonitor;

    /** 是否启用元工具 */
    private Boolean enableMetaTool;

    /** 专家配置（Supervisor 模式） */
    private SkillConfig skillConfig;

    /** 模型配置覆盖（可选） */
    private ModelConfigDto model;

    public ProfileDefinition() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getPromptSuffix() {
        return promptSuffix;
    }

    public void setPromptSuffix(String promptSuffix) {
        this.promptSuffix = promptSuffix;
    }

    public List<String> getToolGroups() {
        return toolGroups;
    }

    public void setToolGroups(List<String> toolGroups) {
        this.toolGroups = toolGroups;
    }

    public List<String> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(List<String> mcpServers) {
        this.mcpServers = mcpServers;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public Boolean getEnablePlanNotebook() {
        return enablePlanNotebook;
    }

    public void setEnablePlanNotebook(Boolean enablePlanNotebook) {
        this.enablePlanNotebook = enablePlanNotebook;
    }

    public Integer getPlanMaxSubtasks() {
        return planMaxSubtasks;
    }

    public void setPlanMaxSubtasks(Integer planMaxSubtasks) {
        this.planMaxSubtasks = planMaxSubtasks;
    }

    public String getPlanPrompt() {
        return planPrompt;
    }

    public void setPlanPrompt(String planPrompt) {
        this.planPrompt = planPrompt;
    }

    public Boolean getEnablePlanMonitor() {
        return enablePlanMonitor;
    }

    public void setEnablePlanMonitor(Boolean enablePlanMonitor) {
        this.enablePlanMonitor = enablePlanMonitor;
    }

    public Boolean getEnableMetaTool() {
        return enableMetaTool;
    }

    public void setEnableMetaTool(Boolean enableMetaTool) {
        this.enableMetaTool = enableMetaTool;
    }

    public SkillConfig getSkillConfig() {
        return skillConfig;
    }

    public void setSkillConfig(SkillConfig skillConfig) {
        this.skillConfig = skillConfig;
    }

    public ModelConfigDto getModel() {
        return model;
    }

    public void setModel(ModelConfigDto model) {
        this.model = model;
    }
}