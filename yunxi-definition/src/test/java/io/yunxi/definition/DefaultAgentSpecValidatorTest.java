package io.yunxi.definition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
                null, null, null, null, null, new AgentSpec.SubagentsConfig(List.of()), null);
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
        // 仅 identity 必填，其余配置段（skill/mcp/state/permission）允许为空
        AgentSpec spec = specWith("agent-1", "Agent One");
        AgentSpec.ValidationResult result = validator.validate(spec);
        assertTrue(result.valid(), "仅有 identity 的极简 spec 应合法");
        assertTrue(result.errors().isEmpty());
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
}
