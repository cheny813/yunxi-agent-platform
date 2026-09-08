package io.yunxi.platform.shared.config;

import java.util.List;

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

    /**
     * 无人值守（DONT_ASK）模式下显式放行的业务工具白名单。
     * 命中即 ALLOW，其余工具仍走兜底 DENY（含 write_file/edit_file/execute 等危险工具，不可列入）。
     * 用于让纯查询/评分类 Agent 在无人值守时正常调用其 MCP / 表单 / 数据库工具，
     * 而不必退化为 BYPASS（全放行）导致危险工具可被静默执行。
     */
    private List<String> allowedTools;

    /**
     * 无人值守（BYPASS）模式下显式拒绝的业务工具黑名单。
     * 与 {@code allowedTools} 白名单互补：白名单须逐一列出"允许"的工具（漏列即 DENY，
     * 且 McpTool 在 DONT_ASK 下不会被框架自检放行，必须显式声明）；
     * 黑名单只需列出"禁止"的少数危险工具（如 node_command），其余全部放行，配置量更少。
     * 平台级危险工具基线（见 PermissionConfig）始终合并生效，无需在此重复列出。
     */
    private List<String> deniedTools;

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

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools;
    }

    public List<String> getDeniedTools() {
        return deniedTools;
    }

    public void setDeniedTools(List<String> deniedTools) {
        this.deniedTools = deniedTools;
    }
}