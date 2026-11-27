package io.yunxi.definition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 定义层门禁验证：{@link DefaultAgentSpecValidator} 守护最低的「身份」结构契约。
 * 合法 spec 必须通过；缺失必填字段必须被拒并给出明确错误，避免非法定义流入运行时装配。
 */
class DefaultAgentSpecValidatorTest {

    private final DefaultAgentSpecValidator validator = new DefaultAgentSpecValidator();

    private static AgentSpec specWith(String agentId, String name) {
        return new AgentSpec(
                new AgentSpec.Identity(agentId, name, "d"),
                null, null, null, whitelist("memory"), null,
                new AgentSpec.SubagentsConfig(List.of()), null);
    }

    /** 合法的白名单权限：只放行 memory，其余在无人值守下按 DENY 处理。 */
    private static AgentSpec.PermissionConfig whitelist(String... tools) {
        return new AgentSpec.PermissionConfig(null, null, List.of(tools), null);
    }

    @Test
    void blankToolName_rejectedInsteadOfThrowing() {
        // YAML 里写成 `- ` 或 `[null]` 会反序列化出 null 元素。这类条目永远匹配不到任何工具，
        // 属于「配了但没生效」，必须作为校验错误报出；校验器本身绝不允许抛 NPE。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null,
                new AgentSpec.PermissionConfig(null, null, java.util.Arrays.asList("memory", null), null),
                null, new AgentSpec.SubagentsConfig(List.of()), null);

        AgentSpec.ValidationResult result = validator.validate(spec);

        assertFalse(result.valid(), "空白工具名必须被拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("blank")), result.errors().toString());
    }

    @Test
    void validSpec_passes() {
        AgentSpec spec = specWith("agent-1", "Agent One");
        assertTrue(validator.validate(spec).valid(), "合法 spec 应校验通过");
    }

    @Test
    void nullSpec_fails() {
        AgentSpec.ValidationResult result = validator.validate(null);
        assertFalse(result.valid());
        assertTrue(result.errors().contains("agentSpec is null"));
    }

    @Test
    void missingAgentId_fails() {
        AgentSpec.ValidationResult result = validator.validate(specWith("", "Agent One"));
        assertFalse(result.valid());
        assertTrue(result.errors().contains("identity.agentId is required"));
    }

    @Test
    void missingName_fails() {
        AgentSpec.ValidationResult result = validator.validate(specWith("agent-1", ""));
        assertFalse(result.valid());
        assertTrue(result.errors().contains("identity.name is required"));
    }

    @Test
    void optionalSectionsAbsent_stillValid() {
        // 除 identity 与 permission 外，其余配置段（skill/mcp/state/toolkit）允许为空
        AgentSpec spec = specWith("agent-1", "Agent One");
        AgentSpec.ValidationResult result = validator.validate(spec);
        assertTrue(result.valid(), "未声明可选段不应导致非法：" + result.errors());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void permissionAbsent_rejected() {
        // 权限缺失时运行时会退回 DONT_ASK 最大放行，属于「缺省即放权」，必须在定义期拒绝。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null, null, null, new AgentSpec.SubagentsConfig(List.of()), null);

        AgentSpec.ValidationResult result = validator.validate(spec);
        assertFalse(result.valid(), "缺 permission 段必须被拒，不能静默取最大放行");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("permission")), result.errors().toString());
    }

    @Test
    void emptyPermissionShell_rejected() {
        // permission: {} 这种空壳既没选白名单也没选黑名单：无人值守下的默认行为无从判断，不能静默放行。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null,
                new AgentSpec.PermissionConfig(null, null, null, null),
                null, new AgentSpec.SubagentsConfig(List.of()), null);

        AgentSpec.ValidationResult result = validator.validate(spec);
        assertFalse(result.valid(), "空壳 permission 必须被拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("allowedTools") || e.contains("deniedTools")),
                result.errors().toString());
    }

    @Test
    void allowedAndDeniedTogetherWithoutAsk_rejected() {
        // 无人值守下白名单与黑名单的「其余工具」语义相反（DENY / ALLOW），同时声明必然有一半意图被丢弃。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null,
                new AgentSpec.PermissionConfig(null, null, List.of("memory"), List.of("rm")),
                null, new AgentSpec.SubagentsConfig(List.of()), null);

        AgentSpec.ValidationResult result = validator.validate(spec);
        assertFalse(result.valid(), "白名单与黑名单不得在无人值守模式下同时声明");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("allowedTools")), result.errors().toString());
    }

    @Test
    void allowedAndDeniedTogether_areAllowedWhenSomeoneIsOnDuty() {
        // 有人值守（ASK）下两者语义不冲突：允许的免确认、禁止的硬拒、其余走确认。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null,
                new AgentSpec.PermissionConfig(
                        new AgentSpec.PermissionConfig.ToolGate(true, List.of("shell")),
                        null, List.of("memory"), List.of("rm")),
                null, new AgentSpec.SubagentsConfig(List.of()), null);

        AgentSpec.ValidationResult result = validator.validate(spec);
        assertTrue(result.valid(), "有人值守下三者可共存：" + result.errors());
    }

    @Test
    void toolDeclaredInBothAllowedAndAsk_rejected() {
        // 框架判定顺序是 deny → ask → allow，同名出现在 allow 与 ask 时实际按 ASK 执行：
        // 作者写了「放行」却得到「每次都要确认」，同属意图与结果不一致，须在定义期拒绝而不是靠优先级猜。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null,
                new AgentSpec.PermissionConfig(
                        new AgentSpec.PermissionConfig.ToolGate(true, List.of("memory")),
                        null, List.of("memory"), null),
                null, new AgentSpec.SubagentsConfig(List.of()), null);

        AgentSpec.ValidationResult result = validator.validate(spec);
        assertFalse(result.valid(), "同一工具不得同时出现在 allowedTools 与 toolGate.tools");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("memory")), result.errors().toString());
    }

    @Test
    void denylistOnly_isValid() {
        // 工具池很大时（如 100 个里只禁 5 个）黑名单是唯一可维护的表达方式，必须合法。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null,
                new AgentSpec.PermissionConfig(null, null, null, List.of("execute", "write_file")),
                null, new AgentSpec.SubagentsConfig(List.of()), null);

        AgentSpec.ValidationResult result = validator.validate(spec);
        assertTrue(result.valid(), "纯黑名单应合法：" + result.errors());
    }

    @Test
    void emptyWhitelist_isValid() {
        // 「除 MCP 订阅与平台治理工具外一律不放行」是纯 MCP 型 Agent 的正常形态，allowedTools: [] 须合法。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null,
                new AgentSpec.PermissionConfig(null, null, List.of(), null),
                null, new AgentSpec.SubagentsConfig(List.of()), null);

        AgentSpec.ValidationResult result = validator.validate(spec);
        assertTrue(result.valid(), "显式空白名单应合法：" + result.errors());
    }

    @Test
    void invalidSandboxBackend_rejected() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-x", "X", "d"),
                null, null, null, null, null,
                new AgentSpec.SubagentsConfig(List.of()),
                new AgentSpec.SandboxConfig("podman", null, null, null, null, null,
                        null, null, null, "SESSION", null));
        assertFalse(validator.validate(spec).valid(), "非法沙箱后端（podman）须被拒绝");
    }

    @Test
    void validGenerateExtraBody_passes() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-gen", "Gen Agent", "d"),
                null, null, null, whitelist("memory"), null,
                new AgentSpec.SubagentsConfig(List.of()),
                null, null, null, null, null,
                new AgentSpec.GenerateConfig(Map.of("enable_thinking", false)));

        assertTrue(validator.validate(spec).valid(),
                "合法的 generate.extra-body 不应导致非法");
    }

    @Test
    void blankExtraBodyKeyOrNullOrNullValue_rejected() {
        // extra-body 逐键透传到推理服务请求体：空白键/空值是「配了但没生效」，null 值会把无效载荷
        // 打进请求体，都须在定义期拒绝，而不是运行期悄悄发一个畸形字段。
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put(" ", false);
        bad.put("enable_thinking", null);
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-gen", "Gen Agent", "d"),
                null, null, null, whitelist("memory"), null,
                new AgentSpec.SubagentsConfig(List.of()),
                null, null, null, null, null,
                new AgentSpec.GenerateConfig(bad));

        AgentSpec.ValidationResult result = validator.validate(spec);
        assertFalse(result.valid(), "空白键或 null 值的 extra-body 条目须被拒绝");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("generate.extra-body")),
                result.errors().toString());
    }
}
