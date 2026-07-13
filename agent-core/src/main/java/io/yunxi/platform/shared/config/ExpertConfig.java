package io.yunxi.platform.shared.config;

/**
 * 专家 Agent 配置 - Supervisor / Routing 模式的子 Agent 定义
 * <p>
 * 每个专家对应一个已注册的 Agent 实例，由 Supervisor 或 Router 按需调用。
 * 支持嵌套编排：子 Agent 可以有自己的 orchestration 配置声明（如 Pipeline 中的阶段）。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class ExpertConfig {

    /** 专家 Agent 名称（必须与 Agent 定义中的 name 一致） */
    private String name;

    /** 专家描述（LLM 据此决定是否调用该专家，非常重要） */
    private String description;

    /**
     * 嵌套编排配置（可选）
     * <p>
     * 当专家内部也是一个编排流程时，在此定义。
     * 例如：Supervisor 的一个子 Agent 内部是 Pipeline 流程。
     * </p>
     */
    private OrchestrationConfig orchestration;

    /**
     * 是否透传子 Agent 事件到 Supervisor 的事件流（默认 true）。
     * <p>
     * true：子 Agent 执行过程中产生的原生 AgentEvent（工具调用、思考、文本等）
     * 会透出到 Supervisor 流，前端可完整观测子 Agent 行为；
     * false：仅透出 SubAgent 暴露事件（SUBAGENT_EXPOSED），抑制子 Agent 内部明细，
     * 适用于子 Agent 事件噪声过大或不希望前端感知内部细节的场景。
     * </p>
     */
    private Boolean forwardEvents = Boolean.TRUE;

    public ExpertConfig() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public OrchestrationConfig getOrchestration() {
        return orchestration;
    }

    public void setOrchestration(OrchestrationConfig orchestration) {
        this.orchestration = orchestration;
    }

    public Boolean getForwardEvents() {
        return forwardEvents;
    }

    public void setForwardEvents(Boolean forwardEvents) {
        this.forwardEvents = forwardEvents;
    }
}