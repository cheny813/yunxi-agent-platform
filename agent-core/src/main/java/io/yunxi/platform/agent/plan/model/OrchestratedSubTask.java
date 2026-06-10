package io.yunxi.platform.agent.plan.model;

import java.util.List;

/**
 * 编排子任务定义，用于计划模板中的任务描述。
 *
 * <p>
 * 从 YAML 配置映射到 agentscope 的 SubTask，额外包含 pattern 和 agents 字段，
 * 用于支持编排场景（如 Supervisor、Pipeline 等模式）下的子任务配置。
 * 由 PlanTemplateLoader 创建 agentscope Plan 时使用。
 * </p>
 */
public class OrchestratedSubTask {

    /** 任务名称，用于标识和引用子任务 */
    private String name;

    /** 任务描述，说明子任务的目标和执行内容 */
    private String description;

    /** 预期结果，描述子任务完成后的预期输出 */
    private String expectedOutcome;

    /**
     * 编排模式: SUPERVISOR | PIPELINE | ROUTING | WORKFLOW | HANDOFFS | MSG_HUB。
     * 对应 OrchestrationConfig.pattern 配置，决定子任务的执行方式。
     */
    private String pattern;

    /** 参与执行的 Agent ID 列表（用于编排场景，指定哪些 Agent 负责此子任务） */
    private List<String> agents;

    /**
     * 获取任务名称。
     *
     * @return 任务名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置任务名称。
     *
     * @param name 任务名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取任务描述。
     *
     * @return 任务描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置任务描述。
     *
     * @param description 任务描述
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 获取预期结果。
     *
     * @return 预期结果描述
     */
    public String getExpectedOutcome() {
        return expectedOutcome;
    }

    /**
     * 设置预期结果。
     *
     * @param expectedOutcome 预期结果描述
     */
    public void setExpectedOutcome(String expectedOutcome) {
        this.expectedOutcome = expectedOutcome;
    }

    /**
     * 获取编排模式。
     *
     * @return 编排模式标识
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * 设置编排模式。
     *
     * @param pattern 编排模式标识
     */
    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    /**
     * 获取参与执行的 Agent ID 列表。
     *
     * @return Agent ID 列表
     */
    public List<String> getAgents() {
        return agents;
    }

    /**
     * 设置参与执行的 Agent ID 列表。
     *
     * @param agents Agent ID 列表
     */
    public void setAgents(List<String> agents) {
        this.agents = agents;
    }
}
