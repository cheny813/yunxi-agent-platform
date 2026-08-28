package io.yunxi.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.yunxi.platform.shared.config.HITLConfig;
import io.yunxi.platform.shared.config.ToolGateConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务清单工具（{@code todo_write}）在权限引擎中的放行规则测试。
 *
 * <p>背景：AgentScope 权限引擎的判定顺序为
 * {@code deny → ask → tool 自检 → allow → BYPASS → 兜底判定}，
 * 兜底判定在 {@code DEFAULT} 模式返回 ASK（挂起等确认，请求直接结束）、
 * 在 {@code DONT_ASK} 模式返回 DENY（工具直接被拒）。
 * {@code todo_write} 无显式规则时会落到兜底判定，导致任务清单在两种模式下均不可用。
 *
 * <p>本测试守护修复：权限上下文必须始终携带 {@code todo_write} 的 ALLOW 规则
 * （命中于第 4 步，先于兜底判定）。
 */
class PermissionConfigTodoListTest {

    private static final String TODO_WRITE = "todo_write";
    private static final String SHELL_EXECUTE = "shell_execute";

    private PermissionConfig permissionConfig;

    @BeforeEach
    void setUp() {
        permissionConfig = new PermissionConfig();
    }

    /** 构造启用了 ToolGate 的 HITL 配置（对应配了 HITL 的 Agent，模式为 DEFAULT） */
    private HITLConfig hitlWithToolGate() {
        ToolGateConfig toolGate = new ToolGateConfig();
        toolGate.setEnabled(true);
        toolGate.setTools(List.of(SHELL_EXECUTE));
        HITLConfig hitl = new HITLConfig();
        hitl.setToolGate(toolGate);
        return hitl;
    }

    private PermissionRule allowRuleFor(PermissionContextState ctx, String toolName) {
        Map<String, List<PermissionRule>> allowRules = ctx.getAllowRules();
        if (allowRules == null) {
            return null;
        }
        List<PermissionRule> rules = allowRules.get(toolName);
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        return rules.get(0);
    }

    @Test
    @DisplayName("DEFAULT 模式（配了 HITL）：放行 todo_write，否则会挂起等确认导致任务清单无法写入")
    void defaultModeAllowsTodoWrite() {
        PermissionContextState ctx = permissionConfig.build(hitlWithToolGate(), PermissionMode.DEFAULT);

        assertThat(ctx.getMode()).isEqualTo(PermissionMode.DEFAULT);
        PermissionRule rule = allowRuleFor(ctx, TODO_WRITE);
        assertThat(rule).as("DEFAULT 模式必须放行 todo_write，否则会落到兜底 ASK 挂起").isNotNull();
        assertThat(rule.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(rule.toolName()).isEqualTo(TODO_WRITE);
    }

    @Test
    @DisplayName("DONT_ASK 模式：仍需放行 todo_write，否则会被兜底判定直接拒绝")
    void dontAskModeAllowsTodoWrite() {
        PermissionContextState ctx = permissionConfig.build(hitlWithToolGate(), PermissionMode.DONT_ASK);

        assertThat(ctx.getMode()).isEqualTo(PermissionMode.DONT_ASK);
        PermissionRule rule = allowRuleFor(ctx, TODO_WRITE);
        assertThat(rule).as("DONT_ASK 模式必须放行 todo_write，否则兜底判定会转为 DENY").isNotNull();
        assertThat(rule.behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    @DisplayName("无人值守上下文：携带 todo_write 放行规则")
    void unattendedContextAllowsTodoWrite() {
        PermissionContextState ctx = permissionConfig.unattendedContext();

        assertThat(ctx.getMode()).isEqualTo(PermissionMode.DONT_ASK);
        PermissionRule rule = allowRuleFor(ctx, TODO_WRITE);
        assertThat(rule).as("无人值守上下文必须放行 todo_write").isNotNull();
        assertThat(rule.behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    @DisplayName("BYPASS 模式：同样携带 todo_write 放行规则（策略一致）")
    void bypassModeAllowsTodoWrite() {
        PermissionContextState ctx = permissionConfig.build(hitlWithToolGate(), PermissionMode.BYPASS);

        PermissionRule rule = allowRuleFor(ctx, TODO_WRITE);
        assertThat(rule).isNotNull();
        assertThat(rule.behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    @DisplayName("放行 todo_write 不影响 ToolGate 工具的 ASK 规则（不削弱既有人工确认）")
    void toolGateAskRulesUnchanged() {
        PermissionContextState ctx = permissionConfig.build(hitlWithToolGate(), PermissionMode.DEFAULT);

        Map<String, List<PermissionRule>> askRules = ctx.getAskRules();
        assertThat(askRules).containsKey(SHELL_EXECUTE);
        assertThat(askRules.get(SHELL_EXECUTE).get(0).behavior()).isEqualTo(PermissionBehavior.ASK);
        // todo_write 不应被登记为需人工确认
        assertThat(askRules).doesNotContainKey(TODO_WRITE);
    }

    @Test
    @DisplayName("DONT_ASK 模式不注入任何 ASK 规则（避免无人值守场景挂起），但 ALLOW 仍存在")
    void dontAskModeHasNoAskRules() {
        PermissionContextState ctx = permissionConfig.build(hitlWithToolGate(), PermissionMode.DONT_ASK);

        Map<String, List<PermissionRule>> askRules = ctx.getAskRules();
        assertThat(askRules == null || askRules.isEmpty()).as("DONT_ASK 不应注入 ASK 规则").isTrue();
        assertThat(allowRuleFor(ctx, TODO_WRITE)).as("ALLOW 规则不受该约束，仍应存在").isNotNull();
    }

    @Test
    @DisplayName("放行规则不设匹配条件（ruleContent 为空），确保命中该工具的全部调用")
    void allowRuleHasNoContentMatcher() {
        PermissionContextState ctx = permissionConfig.unattendedContext();

        PermissionRule rule = allowRuleFor(ctx, TODO_WRITE);
        assertThat(rule.ruleContent()).as("ruleContent 为空时权限引擎无条件匹配").isNull();
    }
}
