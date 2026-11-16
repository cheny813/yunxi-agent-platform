package io.yunxi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.DefaultAgentSpecValidator;
import io.yunxi.runtime.AgentDefinitionLoader;
import io.yunxi.runtime.permission.PermissionTemplateResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 权限端到端验证：定义文件 → 加载与校验 → 权限模板翻译 → 框架原生判定引擎的真实工具调用决策。
 *
 * <p>{@code PermissionTemplateResolverTest} 只断言 {@code PermissionContextState} 里有哪些规则；
 * 本测试把规则交给框架的 {@link PermissionEngine} 实际判定，验证「写在 yml 里的权限」最终变成
 * ALLOW / DENY / ASK。这能抓住「规则对象存在但语义与作者意图相反」这类单元测试看不出的缺陷
 * ——例如空白名单 {@code allowedTools: []} 曾因判空条件错误派生为 BYPASS，规则内容看起来完全合理，
 * 只有让引擎判定业务工具才会暴露它其实是全放行。</p>
 */
class PermissionEndToEndTest {

    private final PermissionTemplateResolver resolver = new PermissionTemplateResolver();

    @Test
    void publishedEmptyWhitelist_deniesBusinessToolsButAllowsMcpAndGovernance() {
        // 真实链路：classpath 发布包定义 → AgentDefinitionLoader（严格解析 + 必填校验）→ 配置中心。
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        new AgentDefinitionLoader(center, new DefaultAgentSpecValidator()).load();
        AgentSpec spec = center.listAgentSpecs().stream()
                .filter(s -> "text2sql-assistant".equals(s.identity().agentId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("发布包中应有 text2sql-assistant 定义"));

        // 该定义的 allowedTools: [] 表示「只放行 MCP 订阅工具 + 平台治理工具」。
        PermissionEngine engine = new PermissionEngine(
                resolver.resolve(spec.permission(), List.of("text2sql_execute")));

        assertEquals(PermissionBehavior.DENY, decision(engine, "read_file"),
                "显式空白名单下，框架默认工具必须被拒（若派生为 BYPASS 这里会是 ALLOW）");
        assertEquals(PermissionBehavior.ALLOW, decision(engine, "text2sql_execute"),
                "MCP 订阅工具必须放行");
        assertEquals(PermissionBehavior.ALLOW, decision(engine, "todo_write"),
                "平台治理工具在所有模式下放行");
    }

    @Test
    void denylistBlocksOnlyListedTools() {
        // 100 个工具只禁 5 个的场景：黑名单是合理选择，未列出的工具保持放行。
        AgentSpec.PermissionConfig permission =
                new AgentSpec.PermissionConfig(null, null, null, List.of("execute", "write_file"));
        PermissionEngine engine = new PermissionEngine(resolver.resolve(permission, List.of()));

        assertEquals(PermissionBehavior.DENY, decision(engine, "execute"));
        assertEquals(PermissionBehavior.DENY, decision(engine, "write_file"));
        assertEquals(PermissionBehavior.ALLOW, decision(engine, "read_file"),
                "黑名单未列出的工具应放行（BYPASS 兜底）");
    }

    @Test
    void whitelistBlocksUnlistedTools() {
        AgentSpec.PermissionConfig permission =
                new AgentSpec.PermissionConfig(null, null, List.of("memory"), null);
        PermissionEngine engine = new PermissionEngine(resolver.resolve(permission, List.of()));

        assertEquals(PermissionBehavior.ALLOW, decision(engine, "memory"));
        assertEquals(PermissionBehavior.DENY, decision(engine, "read_file"));
    }

    @Test
    void askModeSuspendsConfiguredTool() {
        AgentSpec.PermissionConfig permission = new AgentSpec.PermissionConfig(
                new AgentSpec.PermissionConfig.ToolGate(true, List.of("shell"), "confirm"),
                null, null, null);
        PermissionEngine engine = new PermissionEngine(resolver.resolve(permission, List.of()));

        assertEquals(PermissionBehavior.ASK, decision(engine, "shell"));
        assertEquals(PermissionBehavior.ASK, decision(engine, "read_file"),
                "DEFAULT 模式下未列规则的工具兜底为 ASK，须人工确认");
    }

    @Test
    void emptyShellFailsClosedAtFrameworkLevel() {
        // 校验器会在定义期拒绝空壳 permission；这里验证解析器兜底同样是失败封闭，形成双防线。
        AgentSpec.PermissionConfig permission = new AgentSpec.PermissionConfig(null, null, null, null);
        PermissionEngine engine = new PermissionEngine(resolver.resolve(permission, List.of()));

        assertEquals(PermissionBehavior.DENY, decision(engine, "read_file"));
        assertEquals(PermissionBehavior.DENY, decision(engine, "execute"));
    }

    /** 让框架权限引擎对真实工具对象做判定，工具自身不表态（PASSTHROUGH），由规则决定结果。 */
    private static PermissionBehavior decision(PermissionEngine engine, String toolName) {
        PermissionDecision decision = engine.checkPermission(new ProbeTool(toolName), Map.of()).block();
        assertNotNull(decision, "权限判定不应为空: " + toolName);
        return decision.getBehavior();
    }

    private static final class ProbeTool extends ToolBase {
        ProbeTool(String name) {
            super(name, name + " description", Map.of("type", "object", "properties", Map.of()),
                    false, true, false, null, false, false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough("probe"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.error(new UnsupportedOperationException("权限判定测试不执行工具"));
        }
    }
}
