package io.yunxi.platform.shared.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 计划能力配置（计划能力由 AgentScope
 * PlanModeMiddleware + PlanModeManager 提供）。
 * <p>
 * 本 DTO 仅保留"是否启用计划模式 / 是否需要用户确认"等声明式开关，
 * 实际计划生命周期由 {@code PlanModeMiddleware} 在 Agent 运行时托管。
 * YAML 中若仍配置 {@code plan.templates} 等旧字段，Jackson 会因无对应属性而忽略，不影响启动。
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanConfig {

    /** 是否启用 AgentScope PlanMode 计划模式 */
    private boolean enabled = false;

    /** 计划是否需要用户确认（AgentScope 通过 readOnlyResolver 控制只读阶段） */
    private boolean userConfirm = false;

    /** 最大子任务数量 */
    private Integer maxSubtasks;

    /** 自定义 Planner 提示词 */
    private String prompt;

    /** 是否启用 Plan 可视化监控 */
    private boolean enableMonitor = false;

    public PlanConfig() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUserConfirm() {
        return userConfirm;
    }

    public void setUserConfirm(boolean userConfirm) {
        this.userConfirm = userConfirm;
    }

    public Integer getMaxSubtasks() {
        return maxSubtasks;
    }

    public void setMaxSubtasks(Integer maxSubtasks) {
        this.maxSubtasks = maxSubtasks;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public boolean isEnableMonitor() {
        return enableMonitor;
    }

    public void setEnableMonitor(boolean enableMonitor) {
        this.enableMonitor = enableMonitor;
    }
}