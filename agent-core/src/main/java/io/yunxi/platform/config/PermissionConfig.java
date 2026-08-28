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
 * 将 yunxi 既有 HITL 配置（ToolGate / ReasoningReview）映射为 AgentScope 2.0.0 原生权限上下文。
 *
 * <p>本类<b>不封装</b> AgentScope 的权限模式枚举，{@link PermissionMode} 的 5 种取值
 * （{@code DEFAULT} / {@code ACCEPT_EDITS} / {@code EXPLORE} / {@code BYPASS} / {@code DONT_ASK}）
 * 由框架使用者直接透传，yunxi 不做裁剪。这样既能让使用本框架的用户在特殊场景下选择任意模式
 * （例如本地文件编辑型 agent 用 {@code ACCEPT_EDITS} / {@code EXPLORE}），也能在 AgentScope 升级新增模式时
 * 无需同步修改 yunxi 代码。
 *
 * <p>AgentScope 权限引擎没有独立的 ALLOW / ASK / DENY 语义开关；"需要人工审批"统一通过
 * {@code DEFAULT} 模式叠加针对具体工具的 {@code addAskRule(..., PermissionBehavior.ASK)} 实现：
 * 执行被点名的工具时，权限引擎会挂起并向用户请求确认。
 * yunxi 仅负责把 HITL 配置翻译成上述 ASK 规则，底层放行/拦截语义完全交给 AgentScope。</p>
 *
 * <p>危险路径保护与平台类型无关，由 AgentScope 框架在 {@code ToolBase} / {@code ToolDangerousPathConstants} 层
 * 自动处理：自定义 tool 可在 {@code @Tool} 注解上追加 {@code dangerousFiles} / {@code dangerousDirectories}
 * 把额外路径并入受保护集合，命中后<b>即便在 {@code BYPASS} 模式下也会强制 ASK</b>。yunxi 不重复实现该能力。</p>
 *
 * <p>注意（AgentScope 已知行为）：{@link PermissionMode#DONT_ASK} 下 AgentScope 的 {@code checkAskRules} 不区分 mode，
 * 显式 ASK 规则仍会挂起等应答，导致无人值守场景死锁。因此 {@link #build(HITLConfig, PermissionMode)}
 * 在模式为 {@code DONT_ASK} 时<b>不注入任何 ASK 规则</b>；其安全由 AgentScope 危险路径保护兜底。</p>
 */
@Component
public class PermissionConfig {

    /** 权限规则来源标识，便于在 AgentScope 权限引擎日志/状态中区分 yunxi 注入的规则 */
    private static final String RULE_SOURCE = "yunxi-hitl";

    /**
     * AgentScope 内置任务清单工具名（由 {@code HarnessAgent.Builder.enableTaskList(true)} 注册）。
     */
    private static final String TODO_WRITE_TOOL = "todo_write";

    /**
     * 判断 HITL 配置是否包含需要人工确认（ASK）的工具。
     *
     * <p>ToolGate 启用且工具列表非空，或 ReasoningReview 启用且 ToolGate 含工具，均视为有 ASK 工具。
     * 用于决定透传的 AgentScope 权限模式：有 → {@code DEFAULT}（挂起向用户确认），无 → {@code BYPASS}（全放行）。
     * 同时被 {@link AgentConfigurer#injectHITLMiddlewares}（构建期）与
     * {@code PermissionContextInterceptor}（请求期快照）复用，保证两种注入路径模式决定一致。</p>
     *
     * @param hitl HITL 配置（可为 null）
     * @return 是否配置了需人工确认的工具
     */
    public static boolean hasAskTools(HITLConfig hitl) {
        if (hitl == null) {
            return false;
        }
        ToolGateConfig toolGate = hitl.getToolGate();
        if (toolGate != null && toolGate.isEnabled()
                && toolGate.getTools() != null && !toolGate.getTools().isEmpty()) {
            return true;
        }
        ReasoningReviewConfig reasoningReview = hitl.getReasoningReview();
        if (reasoningReview != null && reasoningReview.isEnabled()
                && toolGate != null && toolGate.getTools() != null && !toolGate.getTools().isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * 按调用方透传的 AgentScope 原生模式构建权限上下文。
     *
     * <p>模式完全由调用方决定（{@link PermissionMode} 5 种均可），yunxi 不裁剪、不重映射。
     * 仅当模式非 {@code DONT_ASK} 时，才把 HITL 配置中的 ToolGate / ReasoningReview 工具
     * 注册为 ASK 规则；{@code DONT_ASK} 为无人值守安全姿态，跳过 ASK 规则以避免挂死。
     *
     * @param hitl HITL 配置（可为 null）
     * @param mode 调用方指定的 AgentScope 原生权限模式（直接透传）
     * @return AgentScope 权限上下文
     */
    public PermissionContextState build(HITLConfig hitl, PermissionMode mode) {
        PermissionContextState.Builder builder = PermissionContextState.builder();
        builder.mode(mode);
        addTodoWriteAllowRule(builder);
        if (mode != PermissionMode.DONT_ASK) {
            addAskRules(builder, hitl);
        }
        return builder.build();
    }

    /**
     * 构建无人值守（{@code DONT_ASK}）权限上下文。
     *
     * <p>用于未配置 HITL 的 Agent：该模式下 AgentScope 兜底判定会把未命中规则的工具调用
     * 从 ASK 降级为 DENY（避免无人值守场景挂起等待）。因此任务清单工具同样必须显式放行，
     * 否则 {@code todo_write} 会被直接拒绝、任务清单完全不可用。
     * 本方法与 {@link #build} 保持一致的放行策略，仅模式固定为 {@code DONT_ASK}。
     *
     * <p>构建期（{@code AgentConfigurer.injectHITLMiddlewares}）与请求期
     * （{@code PermissionContextInterceptor}）两条注入路径均复用本方法，
     * 避免各自构造上下文导致放行策略漏配。</p>
     *
     * <p><b>已知取舍</b>：本模式下未命中 ALLOW 规则的工具一律被拒绝，<b>包括
     * {@code list_files} / {@code read_file} 等只读工具</b>——工具的 {@code readOnly=true}
     * 属性仅在 {@code EXPLORE} / {@code ACCEPT_EDITS} 模式下参与判定，在
     * {@code DONT_ASK} 下不生效。只读 MCP 工具不受影响（{@code McpTool} 工具自检直接放行）。</p>
     *
     * <p><b>为何不用 EXPLORE</b>（语义上更贴近"只读放行"，曾被考虑）：
     * {@code PermissionEngine} 的判定顺序为
     * {@code deny → ask →[(EXPLORE/ACCEPT_EDITS 模式判定) 或 工具自检] → allow → BYPASS → 兜底}，
     * EXPLORE 的模式判定位于 ALLOW 规则<b>之前</b>且立即返回
     * （{@code PermissionEngine.checkExploreMode} L241-248 对非只读工具直接 DENY）。
     * 而任务清单工具 {@code todo_write} 的 {@code readOnly=false}
     * （{@code TodoTools} L96），改用 EXPLORE 会使其被直接 DENY、任务清单失效。
     * 换言之：EXPLORE 与"为特定工具注入 ALLOW 放行"二者不可兼得，
     * 故保留 DONT_ASK + ALLOW 规则的组合。
     * </p>
     *
     * <p>若将来业务确实需要在此模式下使用框架内置只读工具，可行方案是：
     * 在请求期通过 Agent 实例的 toolkit 枚举 {@code isReadOnly()} 为 true 的工具并注入
     * ALLOW 规则（注意不能用装配期的 toolkit——实测其视图不完整：即便在 Agent 构建
     * 完成后，其中也只含装配期注册的工具，框架在 build 阶段注册的工具不在其中）。</p>
     *
     * @return 无人值守权限上下文
     */
    public PermissionContextState unattendedContext() {
        PermissionContextState.Builder builder = PermissionContextState.builder();
        builder.mode(PermissionMode.DONT_ASK);
        addTodoWriteAllowRule(builder);
        return builder.build();
    }

    /**
     * 为任务清单工具注入放行规则（ALLOW）。
     *
     * <p><b>为什么必须显式放行</b>：AgentScope 权限引擎的判定顺序为
     * {@code deny → ask → tool 自检 → allow → BYPASS → 兜底判定}，兜底判定
     * （{@code PermissionEngine.defaultDecisionAsk}）在 {@code DEFAULT} 模式下返回
     * {@code ASK}、在 {@code DONT_ASK} 模式下返回 {@code DENY}。{@code todo_write}
     * 属于写类工具且无显式规则，会一路落到兜底判定：
     *
     * <ul>
     *   <li>配了 HITL 的 Agent（{@code DEFAULT} 模式）→ 触发人工确认并挂起，Agent 停止推进，
     *       请求直接结束，任务清单永远无法写入；</li>
     *   <li>未配 HITL 的 Agent（{@code DONT_ASK} 模式）→ 直接被拒绝执行，任务清单完全不可用。</li>
     * </ul>
     *
     * 由于 ALLOW 规则命中于第 4 步、早于兜底判定，注入该规则即可让 {@code todo_write}
     * 在任何权限模式下正常执行。
     * </p>
     *
     * <p><b>安全性</b>：{@code todo_write} 仅读写当前会话的
     * {@code AgentState.tasksContext}，不落盘业务数据、不执行命令、不访问网络，
     * 与 {@code shell_execute} / {@code file_write} 等有外部副作用的工具有本质区别，
     * 故不纳入人工确认范围。该规则仅在 Agent 启用任务清单
     * （{@code plan.taskList=true}）注册了该工具后才可能命中；未启用时无副作用。
     * </p>
     *
     * <p>注：本规则为 ALLOW 而非 ASK，不受 {@link #build} 中
     * “{@code DONT_ASK} 模式不注入 ASK 规则”的约束（ALLOW 不会挂起等待）。</p>
     */
    private void addTodoWriteAllowRule(PermissionContextState.Builder builder) {
        builder.addAllowRule(TODO_WRITE_TOOL,
                new PermissionRule(TODO_WRITE_TOOL, null, PermissionBehavior.ALLOW, RULE_SOURCE));
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
