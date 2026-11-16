package io.yunxi.definition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 定义平面门禁验证：非法调度配置必须在流入运行时装配前被拒绝。
 */
class DefaultAgentSpecValidatorScheduleTest {

    private final DefaultAgentSpecValidator validator = new DefaultAgentSpecValidator();

    private static AgentSpec specWith(AgentSpec.ScheduleConfig schedule) {
        return new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null,
                new AgentSpec.PermissionConfig(null, null, List.of("memory"), null),
                null,
                new AgentSpec.SubagentsConfig(List.of()), null, schedule);
    }

    @Test
    void validCronSchedulePasses() {
        AgentSpec.ValidationResult result = validator.validate(specWith(
                new AgentSpec.ScheduleConfig("CRON", "0 0 8 * * ?", null, null, null, "UTC", "p", "ops")));
        assertTrue(result.valid(), "合法 CRON 应通过：" + result.errors());
    }

    @Test
    void validFixedRateAndDelayPass() {
        assertTrue(validator.validate(specWith(
                new AgentSpec.ScheduleConfig("FIXED_RATE", null, 60000L, null, null, null, "p", "ops"))).valid());
        assertTrue(validator.validate(specWith(
                new AgentSpec.ScheduleConfig("FIXED_DELAY", null, null, 60000L, null, null, "p", "ops"))).valid());
    }

    @Test
    void cronWithoutExpressionRejected() {
        AgentSpec.ValidationResult result = validator.validate(specWith(
                new AgentSpec.ScheduleConfig("CRON", "  ", null, null, null, null, "p", "ops")));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("schedule.cron")), result.errors().toString());
    }

    @Test
    void nonPositiveRateRejected() {
        assertFalse(validator.validate(specWith(
                new AgentSpec.ScheduleConfig("FIXED_RATE", null, 0L, null, null, null, "p", "ops"))).valid());
        assertFalse(validator.validate(specWith(
                new AgentSpec.ScheduleConfig("FIXED_RATE", null, null, null, null, null, "p", "ops"))).valid());
    }

    @Test
    void nonPositiveDelayRejected() {
        assertFalse(validator.validate(specWith(
                new AgentSpec.ScheduleConfig("FIXED_DELAY", null, null, -1L, null, null, "p", "ops"))).valid());
    }

    @Test
    void tooSmallIntervalRejected() {
        assertFalse(validator.validate(specWith(
                new AgentSpec.ScheduleConfig("FIXED_RATE", null, 1L, null, null, null, "p", "ops"))).valid(),
                "1ms 周期会占满调度线程池");
        assertFalse(validator.validate(specWith(
                new AgentSpec.ScheduleConfig("FIXED_DELAY", null, null, 999L, null, null, "p", "ops"))).valid());
    }

    @Test
    void unknownModeRejected() {
        AgentSpec.ValidationResult result = validator.validate(specWith(
                new AgentSpec.ScheduleConfig("EVERY_SUNDAY", null, null, null, null, null, "p", "ops")));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("schedule.mode")), result.errors().toString());
    }

    @Test
    void negativeInitialDelayRejected() {
        assertFalse(validator.validate(specWith(
                new AgentSpec.ScheduleConfig("CRON", "0 0 8 * * ?", null, null, -5L, null, "p", "ops"))).valid());
    }

    @Test
    void invalidZoneIdRejected() {
        AgentSpec.ValidationResult result = validator.validate(specWith(
                new AgentSpec.ScheduleConfig(
                        "CRON", "0 0 8 * * ?", null, null, null, "Asia/Shangahi", "p", "ops")));
        assertFalse(result.valid(), "错误时区不得静默回退到 GMT");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("schedule.zoneId")),
                result.errors().toString());
    }

    @Test
    void validZoneIdPasses() {
        assertTrue(validator.validate(specWith(
                new AgentSpec.ScheduleConfig(
                        "CRON", "0 0 8 * * ?", null, null, null, "Asia/Shanghai", "p", "ops"))).valid());
    }

    @Test
    void absentScheduleStillValid() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "d"),
                null, null, null,
                new AgentSpec.PermissionConfig(null, null, List.of("memory"), null),
                null,
                new AgentSpec.SubagentsConfig(List.of()), null);
        assertTrue(validator.validate(spec).valid(), "不声明 schedule 的既有定义必须保持合法");
    }
}
