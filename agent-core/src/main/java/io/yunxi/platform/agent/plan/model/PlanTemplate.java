package io.yunxi.platform.agent.plan.model;

import java.util.List;

/**
 * 计划模板，从 AgentDefinition YAML 加载。
 *
 * <p>
 * 配置文件中定义计划模板的结构，包含 match 匹配规则和子任务列表。
 * 运行时通过 match 规则自动匹配用户意图，创建对应的 agentscope Plan。
 * </p>
 *
 * <p>
 * YAML 配置示例：
 * 
 * <pre>
 * plan:
 *   enabled: true
 *   templates:
 *     - match: "分析报告"
 *       name: "报告分析计划"
 *       description: "分析并生成报告的执行计划"
 *       subtasks:
 *         - name: "数据收集"
 *           description: "收集相关数据"
 *           expectedOutcome: "数据收集完成"
 *         - name: "分析计算"
 *           description: "进行数据分析"
 *           expectedOutcome: "分析结果生成"
 * </pre>
 * </p>
 */
public class PlanTemplate {

    /** 匹配规则关键字，如 "分析报告"，用户目标包含此关键字即匹配 */
    private String match;

    /** 计划名称，用于标识计划模板 */
    private String name;

    /** 计划描述，说明计划的用途和执行内容 */
    private String description;

    /** 计划子任务列表，定义计划的执行步骤 */
    private List<OrchestratedSubTask> subtasks;

    /**
     * 获取匹配规则关键字。
     *
     * @return 匹配关键字
     */
    public String getMatch() {
        return match;
    }

    /**
     * 设置匹配规则关键字。
     *
     * @param match 匹配关键字
     */
    public void setMatch(String match) {
        this.match = match;
    }

    /**
     * 获取计划名称。
     *
     * @return 计划名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置计划名称。
     *
     * @param name 计划名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取计划描述。
     *
     * @return 计划描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置计划描述。
     *
     * @param description 计划描述
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 获取计划子任务列表。
     *
     * @return 子任务列表
     */
    public List<OrchestratedSubTask> getSubtasks() {
        return subtasks;
    }

    /**
     * 设置计划子任务列表。
     *
     * @param subtasks 子任务列表
     */
    public void setSubtasks(List<OrchestratedSubTask> subtasks) {
        this.subtasks = subtasks;
    }
}
