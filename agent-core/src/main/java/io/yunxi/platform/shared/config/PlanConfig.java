package io.yunxi.platform.shared.config;

import io.yunxi.platform.framework.plan.model.PlanTemplate;
import java.util.List;

/**
 * 计划能力配置 - PlanNotebook
 * <p>
 * 启用后 Agent 会自动将复杂任务拆解为子任务并逐步执行。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class PlanConfig {

    /** 是否启用 PlanNotebook */
    private boolean enabled = true;

    /** 计划是否需要用户确认 */
    private boolean userConfirm = false;

    /** 最大子任务数量 */
    private Integer maxSubtasks;

    /** 自定义 Planner 提示词 */
    private String prompt;

    /** 是否启用 Plan 可视化监控 */
    private boolean enableMonitor = false;

    /** 规划模板列表 — 声明式定义常见任务的规划步骤结构 */
    private List<PlanTemplate> templates;

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

    public List<PlanTemplate> getTemplates() {
        return templates;
    }

    public void setTemplates(List<PlanTemplate> templates) {
        this.templates = templates;
    }
}