package io.yunxi.platform.config;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.yunxi.platform.shared.config.HITLConfig;
import io.yunxi.platform.shared.config.ReasoningReviewConfig;
import io.yunxi.platform.shared.config.ToolGateConfig;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 将 yunxi 既有 HITL 配置（ToolGate / ReasoningReview）映射为 GA 2.0.0 原生权限上下文。
 *
 * <p>GA 权限引擎仅提供 5 种模式（{@link PermissionMode#DEFAULT} / {@link PermissionMode#ACCEPT_EDITS}
 * / {@link PermissionMode#EXPLORE} / {@link PermissionMode#BYPASS} / {@link PermissionMode#DONT_ASK}），
 * 没有独立的 ALLOW / ASK / DENY 语义开关；"需要人工审批"统一通过 {@link PermissionMode#DEFAULT} 模式
 * 叠加针对具体工具的 {@code addAskRule(..., PermissionBehavior.ASK)} 实现：执行被点名的工具时，
 * 权限引擎会挂起并向用户请求确认。</p>
 *
 * <p>映射规则：
 * <ul>
 *   <li>ToolGate 启用的工具 → 各自注册一条 ASK 规则（执行前需人工确认）</li>
 *   <li>ReasoningReview 启用时，"危险工具"取自 ToolGate 的工具清单 → 同样注册 ASK 规则</li>
 *   <li>存在任何规则时模式置为 {@link PermissionMode#DEFAULT}：仅被点名的工具需确认，
 *       其余工具经工具自身的 checkPermissions 自检后放行；无任何规则时置为
 *       {@link PermissionMode#BYPASS}（完全放开，等价原 HITL 关闭行为）</li>
 * </ul>
 * </p>
 *
 * <p>本类承接工具门控（ToolGate）与推理审查（ReasoningReview）职责，统一交由框架原生权限引擎处理，
 * 避免重复实现底层能力。</p>
 */
@Component
public class PermissionConfig {

    /** 权限规则来源标识，便于在 GA 权限引擎日志/状态中区分 yunxi 注入的规则 */
    private static final String RULE_SOURCE = "yunxi-hitl";

    /**
     * 根据 HITL 配置构建 GA 权限上下文。
     *
     * @param hitl HITL 配置（可为 null，表示未配置任何人工介入，返回 BYPASS 上下文）
     * @return 可直接传给 {@code HarnessAgent.Builder#permissionContext(...)} 的权限上下文
     */
    public PermissionContextState build(HITLConfig hitl) {
        PermissionContextState.Builder builder = PermissionContextState.builder();
        boolean hasRule = false;

        if (hitl != null) {
            ToolGateConfig toolGate = hitl.getToolGate();
            ReasoningReviewConfig reasoningReview = hitl.getReasoningReview();

            // ToolGate：对配置中的每个工具注册 ASK 规则（执行前需人工确认）
            if (toolGate != null && toolGate.isEnabled()
                    && toolGate.getTools() != null && !toolGate.getTools().isEmpty()) {
                for (String tool : toolGate.getTools()) {
                    builder.addAskRule(tool,
                            new PermissionRule(tool, null, PermissionBehavior.ASK, RULE_SOURCE));
                    hasRule = true;
                }
            }

            // ReasoningReview：将 ToolGate 中的危险工具同样纳入 ASK 规则
            if (reasoningReview != null && reasoningReview.isEnabled()
                    && toolGate != null && toolGate.getTools() != null) {
                for (String tool : Set.copyOf(toolGate.getTools())) {
                    builder.addAskRule(tool,
                            new PermissionRule(tool, null, PermissionBehavior.ASK, RULE_SOURCE));
                    hasRule = true;
                }
            }
        }

        builder.mode(hasRule ? PermissionMode.DEFAULT : PermissionMode.BYPASS);
        return builder.build();
    }
}
