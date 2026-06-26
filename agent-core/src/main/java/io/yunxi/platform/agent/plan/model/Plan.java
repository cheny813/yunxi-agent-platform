package io.yunxi.platform.agent.plan.model;

import java.util.List;

/**
 * 本地 Plan 模型（yunxi 内部 Plan 定义）。
 *
 * <p>
 * V2.0-RC3: 框架已将 {@code io.agentscope.core.plan.model.Plan} 完全删除。
 * yunxi 保留此本地 Plan 模型以支持 YAML 模板匹配功能。
 * 待框架统一 plan 方案上线后迁移。
 * </p>
 */
public class Plan {
    private String name;
    private String description;
    private List<SubTask> subtasks;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<SubTask> getSubtasks() { return subtasks; }
    public void setSubtasks(List<SubTask> subtasks) { this.subtasks = subtasks; }
}
