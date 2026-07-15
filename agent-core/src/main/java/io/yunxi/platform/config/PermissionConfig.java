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
 * <p>本类<b>不封装</b> GA 的权限模式枚举，{@link PermissionMode} 的 5 种取值
 * （{@code DEFAULT} / {@code ACCEPT_EDITS} / {@code EXPLORE} / {@code BYPASS} / {@code DONT_ASK}）
 * 由框架使用者直接透传，yunxi 不做裁剪。这样既能让使用本框架的用户在特殊场景下选择任意模式
 * （例如本地文件编辑型 agent 用 {@code ACCEPT_EDITS} / {@code EXPLORE}），也能在 GA 升级新增模式时
 * 无需同步修改 yunxi 代码。
 *
 * <p>GA 权限引擎没有独立的 ALLOW / ASK / DENY 语义开关；"需要人工审批"统一通过
 * {@code DEFAULT} 模式叠加针对具体工具的 {@code addAskRule(..., PermissionBehavior.ASK)} 实现：
 * 执行被点名的工具时，权限引擎会挂起并向用户请求确认。
 * yunxi 仅负责把 HITL 配置翻译成上述 ASK 规则，底层放行/拦截语义完全交给 GA。</p>
 *
 * <p>危险路径保护与平台类型无关，由 GA 框架在 {@code ToolBase} / {@code ToolDangerousPathConstants} 层
 * 自动处理：自定义 tool 可在 {@code @Tool} 注解上追加 {@code dangerousFiles} / {@code dangerousDirectories}
 * 把额外路径并入受保护集合，命中后<b>即便在 {@code BYPASS} 模式下也会强制 ASK</b>。yunxi 不重复实现该能力。</p>
 *
 * <p>注意（GA 已知行为）：{@link PermissionMode#DONT_ASK} 下 GA 的 {@code checkAskRules} 不区分 mode，
 * 显式 ASK 规则仍会挂起等应答，导致无人值守场景死锁。因此 {@link #build(HITLConfig, PermissionMode)}
 * 在模式为 {@code DONT_ASK} 时<b>不注入任何 ASK 规则</b>；其安全由 GA 危险路径保护兜底。</p>
 */
@Component
public class PermissionConfig {

    /** 权限规则来源标识，便于在 GA 权限引擎日志/状态中区分 yunxi 注入的规则 */
    private static final String RULE_SOURCE = "yunxi-hitl";

    /**
     * 按调用方透传的 GA 原生模式构建权限上下文。
     *
     * <p>模式完全由调用方决定（{@link PermissionMode} 5 种均可），yunxi 不裁剪、不重映射。
     * 仅当模式非 {@code DONT_ASK} 时，才把 HITL 配置中的 ToolGate / ReasoningReview 工具
     * 注册为 ASK 规则；{@code DONT_ASK} 为无人值守安全姿态，跳过 ASK 规则以避免挂死。
     *
     * @param hitl HITL 配置（可为 null）
     * @param mode 调用方指定的 GA 原生权限模式（直接透传）
     * @return GA 权限上下文
     */
    public PermissionContextState build(HITLConfig hitl, PermissionMode mode) {
        PermissionContextState.Builder builder = PermissionContextState.builder();
        builder.mode(mode);
        if (mode != PermissionMode.DONT_ASK) {
            addAskRules(builder, hitl);
        }
        return builder.build();
    }

    /**
     * 将 HITL 配置中的 ToolGate / ReasoningReview 工具注册为 ASK 规则。
     *
     * @return 是否注册了任意规则
     */
    private boolean addAskRules(PermissionContextState.Builder builder, HITLConfig hitl) {
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

        return hasRule;
    }
}
