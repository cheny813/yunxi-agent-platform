package io.yunxi.platform.framework.plan.model;

import java.util.List;

/**
 * 规划模板 — 从 AgentDefinition YAML 加载。
 * <p>
 * 定义一类常见任务的预定义规划步骤结构。
 * 当用户请求与 match 模式匹配时，系统自动创建对应的规划。
 * </p>
 */
public class PlanTemplate {

    /** 匹配关键词（如 "月度食谱"） */
    private String match;

    /** 规划名称 */
    private String name;

    /** 规划描述 */
    private String description;

    /** 规划步骤列表 */
    private List<OrchestratedSubTask> subtasks;

    public String getMatch() { return match; }
    public void setMatch(String match) { this.match = match; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<OrchestratedSubTask> getSubtasks() { return subtasks; }
    public void setSubtasks(List<OrchestratedSubTask> subtasks) { this.subtasks = subtasks; }
}