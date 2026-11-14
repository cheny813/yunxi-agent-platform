package io.yunxi.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                new AgentSpec.PermissionConfig.ToolGate(true, List.of("shell"), "confirm"),
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
    void platformToolsAlwaysAllowed() {
        var ctx = resolver.resolve(null);
        assertTrue(ctx.getAllowRules().containsKey("todo_write"), "平台治理工具 todo_write 须放行");
        assertTrue(ctx.getAllowRules().containsKey("muse_skill_eval"), "MUSE 元工具须放行");
    }
}
