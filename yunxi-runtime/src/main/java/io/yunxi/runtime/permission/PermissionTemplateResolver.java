package io.yunxi.runtime.permission;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.yunxi.definition.AgentSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * HITL 权限配置 → AgentScope-Java {@link PermissionContextState} 的纯翻译映射器。
 *
 * <p>本类落在产品平面，是可独立测试的纯函数：输入是产品语义配置
 * {@link AgentSpec.PermissionConfig}，输出是框架原生的 {@code PermissionContextState} 构建期模板。
 * 它不持有任何横切状态、不读写运行时会话、不引入静态单例快照，唯一职责是
 * 把「配置即数据」翻译为「框架即真相」。</p>
 *
 * <p>设计要点（产品平面对权限的收敛）：</p>
 * <ul>
 *   <li>权限模式由 {@link #resolve(AgentSpec.PermissionConfig)} 依据配置自动派生，不直接暴露给 Agent 定义；</li>
 *   <li>平台治理工具（todo_write / MUSE 元工具）在任何模式均放行，避免把治理链路误判为需人工确认；</li>
 *   <li>无人值守黑名单（BYPASS 模式）自动并入 {@code DEFAULT_DENIED_BASELINE}，使宿主代码执行工具
 *       {@code execute} 始终被 DENY，按安全方向收敛（纵深防御；主路径真兜底来自 toolkit 层物理剔除）。</li>
 * </ul>
 *
 * <p>与框架权限判定的对齐（框架六段式：deny → ask → 工具自检 → allow → bypass 兜底 → 默认 ASK）：</p>
 * <ul>
 *   <li>{@code DEFAULT} 模式 + 无规则 → 六段式兜底 ASK（触发 HITL 挂起等用户），故有人值守分支走 DEFAULT；</li>
 *   <li>{@code DONT_ASK} 模式 + 无规则 → 降级为 DENY（失败封闭，非「跳过直接执行」），故无人值守分支凡未显式
 *       ALLOW 的工具一律 DENY；</li>
 *   <li>{@code BYPASS} 模式 → 规则命中 DENY 后直接 ALLOW 兜底，故黑名单分支仅把列出的工具 DENY、其余放行。</li>
 * </ul>
 *
 * <p>职责边界：本解析器只产出「构建期模板」。HITL 确认后运行时 {@code addRule} 回流的持久化与跨实例共享、
 * 待审单据的查询与超时治理，属 AgentScope-Java 权限内核的独立能力，不在本映射器范围。</p>
 */
public class PermissionTemplateResolver {

    /** 规则来源标记，标识该规则由 yunxi 产品平面注入。 */
    private static final String RULE_SOURCE = "yunxi-hitl";

    /** 无人值守黑名单（BYPASS 模式）兜底也禁止的工具基线：宿主代码执行有副作用，必须 DENY。 */
    private static final List<String> DEFAULT_DENIED_BASELINE = List.of("execute");

    /** 平台任务编排治理工具，任何模式放行。 */
    private static final String TODO_WRITE_TOOL = "todo_write";

    /** MUSE 自进化元工具，任何模式放行（仅当 muse 启用时由 SPI 注册，放行无害）。 */
    private static final List<String> MUSE_META_TOOLS =
            List.of("muse_skill_eval", "muse_skill_evolve", "muse_skill_prune");

    /**
     * 入口：依据产品语义配置派生 {@link PermissionMode} 并构建权限上下文模板。
     * null 配置视为「无人值守兜底」（DONT_ASK + 平台放行）。
     */
    public PermissionContextState resolve(AgentSpec.PermissionConfig permission) {
        return resolve(permission, List.of());
    }

    /**
     * 带 MCP 工具名的入口：{@code mcpToolNames} 为本 Agent 实际订阅并装载的 MCP 工具，
     * 由 {@code mcp.servers} / 自动装载派生而来。订阅即授权——这些工具在所有权限模式下均注入 ALLOW，
     * 使 Agent 定义只需声明 MCP 订阅、无需在 allowedTools 里重复列 MCP 工具（装载与放行统一为单一来源）。
     */
    public PermissionContextState resolve(AgentSpec.PermissionConfig permission, List<String> mcpToolNames) {
        if (permission == null) {
            return unattendedContext(mcpToolNames);
        }
        if (hasAskTools(permission)) {
            return build(permission, PermissionMode.DEFAULT, mcpToolNames);
        }
        if (permission.allowedTools() != null && !permission.allowedTools().isEmpty()) {
            return unattendedContext(permission.allowedTools(), mcpToolNames);
        }
        return blacklistContext(permission.deniedTools(), mcpToolNames);
    }

    /** 无人值守兜底：DONT_ASK + 平台治理工具放行 + MCP 订阅工具放行。 */
    private PermissionContextState unattendedContext(List<String> mcpToolNames) {
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK);
        addPlatformToolAllowRules(builder);
        addMcpAllowRules(builder, mcpToolNames);
        return builder.build();
    }

    /** 无人值守白名单：DONT_ASK + 平台放行 + 每个 allowedTool 与 MCP 订阅工具注入 ALLOW。 */
    private PermissionContextState unattendedContext(List<String> allowedTools, List<String> mcpToolNames) {
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK);
        addPlatformToolAllowRules(builder);
        for (String tool : allowedTools) {
            builder.addAllowRule(tool, allowRule(tool));
        }
        addMcpAllowRules(builder, mcpToolNames);
        return builder.build();
    }

    /** 默认有人值守：DEFAULT 模式 + 平台放行 + ASK 规则（挂起向用户确认）+ MCP 订阅工具放行。 */
    private PermissionContextState build(AgentSpec.PermissionConfig hitl, PermissionMode mode, List<String> mcpToolNames) {
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(mode);
        addPlatformToolAllowRules(builder);
        addMcpAllowRules(builder, mcpToolNames);
        if (mode != PermissionMode.DONT_ASK) {
            addAskRules(builder, hitl);
        }
        return builder.build();
    }

    /**
     * 无人值守黑名单：BYPASS + 平台放行 + todo_write 放行 +
     * 合并 deniedTools∪DEFAULT_DENIED_BASELINE 注入 DENY（判定优先级最高，execute 永远 DENY）。
     * MCP 订阅工具仍注入 ALLOW（订阅即授权，优先于黑名单的通用 DENY，但不优先于显式列出的 deniedTools）。
     */
    private PermissionContextState blacklistContext(List<String> deniedTools, List<String> mcpToolNames) {
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(PermissionMode.BYPASS);
        addPlatformToolAllowRules(builder);
        addMcpAllowRules(builder, mcpToolNames);
        List<String> allDenied = new ArrayList<>(DEFAULT_DENIED_BASELINE);
        if (deniedTools != null) {
            allDenied.addAll(deniedTools);
        }
        for (String tool : allDenied) {
            builder.addDenyRule(tool, denyRule(tool));
        }
        return builder.build();
    }

    /** MCP 订阅工具放行：订阅即授权，在所有权限模式下注入 ALLOW。 */
    private void addMcpAllowRules(PermissionContextState.Builder builder, List<String> mcpToolNames) {
        for (String tool : mcpToolNames) {
            builder.addAllowRule(tool, allowRule(tool));
        }
    }

    /** 平台治理工具（todo_write + MUSE 元工具）在任何模式均注入 ALLOW。 */
    private void addPlatformToolAllowRules(PermissionContextState.Builder builder) {
        builder.addAllowRule(TODO_WRITE_TOOL, allowRule(TODO_WRITE_TOOL));
        for (String museTool : MUSE_META_TOOLS) {
            builder.addAllowRule(museTool, allowRule(museTool));
        }
    }

    /** 注入 ASK 规则：toolGate.tools 中列出的工具在调用前挂起向用户确认。 */
    private void addAskRules(PermissionContextState.Builder builder, AgentSpec.PermissionConfig hitl) {
        if (hitl.toolGate() != null && hitl.toolGate().tools() != null) {
            for (String tool : hitl.toolGate().tools()) {
                builder.addAskRule(tool, askRule(tool));
            }
        }
    }

    /** 是否有人值守 ASK 能力。 */
    private static boolean hasAskTools(AgentSpec.PermissionConfig hitl) {
        List<String> tools = hitl.toolGate() != null ? hitl.toolGate().tools() : null;
        boolean toolsNonEmpty = tools != null && !tools.isEmpty();
        if (toolsNonEmpty && hitl.toolGate() != null && hitl.toolGate().enabled()) {
            return true;
        }
        if (toolsNonEmpty && hitl.reasoningReview() != null && hitl.reasoningReview().enabled()) {
            return true;
        }
        return false;
    }

    private PermissionRule allowRule(String tool) {
        return new PermissionRule(tool, null, PermissionBehavior.ALLOW, RULE_SOURCE);
    }

    private PermissionRule denyRule(String tool) {
        return new PermissionRule(tool, null, PermissionBehavior.DENY, RULE_SOURCE);
    }

    private PermissionRule askRule(String tool) {
        return new PermissionRule(tool, null, PermissionBehavior.ASK, RULE_SOURCE);
    }
}
