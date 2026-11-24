package io.yunxi.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.permission.PermissionMode;
import io.yunxi.definition.AgentSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 聚焦单测：锁定 {@link PermissionTemplateResolver} 的 mode 派生与平台治理工具放行。
 * 它不依赖 Spring，是纯函数，可直接独立验证。
 */
class PermissionTemplateResolverTest {

    private final PermissionTemplateResolver resolver = new PermissionTemplateResolver();

    @Test
    void nullConfig_derivesUnattendedDontAsk() {
        var ctx = resolver.resolve(null);
        assertNotNull(ctx);
        assertEquals(PermissionMode.DONT_ASK, ctx.getMode());
    }

    @Test
    void askTools_derivesDefaultWithAskRule() {
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(
                new AgentSpec.PermissionConfig.ToolGate(true, List.of("shell")),
                null, null, null);
        var ctx = resolver.resolve(cfg);
        assertNotNull(ctx);
        assertEquals(PermissionMode.DEFAULT, ctx.getMode());
        assertTrue(ctx.getAskRules().containsKey("shell"), "shell 应注入 ASK 规则");
    }

    @Test
    void allowedTools_derivesUnattendedDontAskWithAllowRule() {
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(
                null, null, List.of("read_file"), null);
        var ctx = resolver.resolve(cfg);
        assertNotNull(ctx);
        assertEquals(PermissionMode.DONT_ASK, ctx.getMode());
        assertTrue(ctx.getAllowRules().containsKey("read_file"), "read_file 应注入 ALLOW 规则");
    }

    @Test
    void deniedTools_derivesBypassWithBaselineDeny() {
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(
                null, null, null, List.of("rm"));
        var ctx = resolver.resolve(cfg);
        assertNotNull(ctx);
        assertEquals(PermissionMode.BYPASS, ctx.getMode());
        // DEFAULT_DENIED_BASELINE 的 execute 必须始终在 DENY 表中（安全方向，纵深防御）
        assertTrue(ctx.getDenyRules().containsKey("execute"), "execute 基线须并入 DENY");
        assertTrue(ctx.getDenyRules().containsKey("rm"), "显式 deniedTools 须 DENY");
    }

    @Test
    void askTools_withAllowedAndDenied_injectsAllThreeRuleKinds() {
        // 有人值守下三种声明语义并不冲突：允许的免确认、禁止的硬拒、门控的走确认。
        // 曾经的实现只注入 ASK 规则，allowedTools / deniedTools 被整段丢弃（写了不生效）。
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(
                new AgentSpec.PermissionConfig.ToolGate(true, List.of("shell")),
                null, List.of("memory"), List.of("rm"));

        var ctx = resolver.resolve(cfg);

        assertEquals(PermissionMode.DEFAULT, ctx.getMode());
        assertTrue(ctx.getAllowRules().containsKey("memory"), "allowedTools 须注入 ALLOW（免确认）");
        assertTrue(ctx.getDenyRules().containsKey("rm"), "deniedTools 须注入 DENY（硬拒，优先于确认）");
        assertTrue(ctx.getAskRules().containsKey("shell"), "toolGate.tools 须注入 ASK");
    }

    @Test
    void emptyWhitelist_deniesBusinessToolsWhileKeepingPlatformAndMcp() {
        // allowedTools: [] 的语义是「除 MCP 订阅工具与平台治理工具外一律拒绝」（纯 MCP 型 Agent 的常态，
        // text2sql-assistant 即为该形态）。曾因判空条件写成 !isEmpty() 而落进黑名单分支（BYPASS），
        // 实际效果恰好相反：只禁 execute，其余全放行。
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(null, null, List.of(), null);

        var ctx = resolver.resolve(cfg, List.of("text2sql_execute"));

        assertEquals(PermissionMode.DONT_ASK, ctx.getMode(), "空白名单必须走无人值守白名单，而非 BYPASS");
        assertFalse(ctx.getAllowRules().containsKey("read_file"), "空白名单下任何业务工具都不得放行");
        assertTrue(ctx.getAllowRules().containsKey("text2sql_execute"), "MCP 订阅工具仍须放行");
        assertTrue(ctx.getAllowRules().containsKey("todo_write"), "平台治理工具仍须放行");
    }

    @Test
    void emptyShellPermission_failsClosedInsteadOfBypass() {
        // permission: {} 三个清单都没声明。校验器已拒绝该形态，此处是第二道防线：
        // 解析器兜底必须失败封闭（DONT_ASK），不能退化成 BYPASS 的「只禁 execute、其余全放行」。
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(null, null, null, null);

        var ctx = resolver.resolve(cfg);

        assertEquals(PermissionMode.DONT_ASK, ctx.getMode(), "空壳权限必须失败封闭，不得兜底为 BYPASS");
        assertFalse(ctx.getAllowRules().containsKey("read_file"), "空壳权限下业务工具不得放行");
    }

    @Test
    void reasoningReviewOnly_derivesDefaultWithAskRule() {
        // 推理复核与 toolGate 是同一套「有工具需确认」的两种开关，派生规则必须一致；
        // 曾有两份各自实现的判定，一处改了另一处忘改即会让校验通过与运行期行为脱节。
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(
                new AgentSpec.PermissionConfig.ToolGate(false, List.of("shell")),
                new AgentSpec.PermissionConfig.ReasoningReview(true, "strict", List.of("rm")),
                null, null);

        var ctx = resolver.resolve(cfg);

        assertEquals(PermissionMode.DEFAULT, ctx.getMode());
        assertTrue(ctx.getAskRules().containsKey("shell"), "推理复核启用时同样须注入 ASK 规则");
    }

    @Test
    void platformToolsAlwaysAllowed() {
        var ctx = resolver.resolve(null);
        assertTrue(ctx.getAllowRules().containsKey("todo_write"), "平台治理工具 todo_write 须放行");
        assertTrue(ctx.getAllowRules().containsKey("muse_skill_eval"), "MUSE 元工具须放行");
    }
}
