package io.yunxi.platform.shared.config;

/**
 * HITL (Human-in-the-Loop) 配置模型
 *
 * <p>控制 Agent 执行过程中人类介入的行为。挂载在 {@link ExtensionConfig#hitl} 下，
 * 通过 Agent YAML 配置驱动三种 HITL 模式的开启/关闭。
 *
 * <pre>
 * extensions:
 *   hitl:
 *     toolGate:
 *       enabled: true
 *       tools: ["delete_file", "exec_command"]
 *     reasoningReview:
 *       enabled: true
 *       strategy: "on-dangerous-tool"
 *     humanTool:
 *       enabled: true
 * </pre>
 *
 * @author yunxi-platform
 */
public class HITLConfig {

    /** 工具门控配置 */
    private ToolGateConfig toolGate = new ToolGateConfig();

    /** 推理审查配置 */
    private ReasoningReviewConfig reasoningReview = new ReasoningReviewConfig();

    /** 人机协作配置 */
    private HumanToolConfig humanTool = new HumanToolConfig();

    public HITLConfig() {}

    public ToolGateConfig getToolGate() {
        return toolGate;
    }

    public void setToolGate(ToolGateConfig toolGate) {
        this.toolGate = toolGate;
    }

    public ReasoningReviewConfig getReasoningReview() {
        return reasoningReview;
    }

    public void setReasoningReview(ReasoningReviewConfig reasoningReview) {
        this.reasoningReview = reasoningReview;
    }

    public HumanToolConfig getHumanTool() {
        return humanTool;
    }

    public void setHumanTool(HumanToolConfig humanTool) {
        this.humanTool = humanTool;
    }
}