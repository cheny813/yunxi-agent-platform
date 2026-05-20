package io.yunxi.platform.framework.plan.model;

import java.util.List;

/**
 * 带编排模式标注的子任务定义。
 * <p>
 * 从 YAML 模板中加载，比 agentscope 的 SubTask 多了 pattern 和 agentIds 字段。
 * 用于 PlanTemplateLoader 创建 agentscope Plan 时的数据源。
 * </p>
 */
public class OrchestratedSubTask {

    /** 子任务名称 */
    private String name;

    /** 子任务描述 */
    private String description;

    /** 预期产出 */
    private String expectedOutcome;

    /**
     * 编排模式: SUPERVISOR | PIPELINE | ROUTING | WORKFLOW | HANDOFFS | MSG_HUB
     * 对应 OrchestrationConfig.pattern 的值
     */
    private String pattern;

    /** 执行该子任务的 Agent ID 列表（模式需要时使用） */
    private List<String> agents;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExpectedOutcome() { return expectedOutcome; }
    public void setExpectedOutcome(String expectedOutcome) { this.expectedOutcome = expectedOutcome; }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }

    public List<String> getAgents() { return agents; }
    public void setAgents(List<String> agents) { this.agents = agents; }
}