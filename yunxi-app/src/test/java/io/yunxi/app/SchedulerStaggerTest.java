package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.yunxi.definition.AgentSpec;
import org.junit.jupiter.api.Test;

/** 错峰与周期模式翻译的验收：直接检查构造出的框架调度配置，而非只断言「不抛异常」。 */
class SchedulerStaggerTest {

    @Test
    void staggerIsLinearAndBounded() {
        assertEquals(1000L, AgentSchedulerBootstrapper.staggerFor(0));
        assertEquals(1500L, AgentSchedulerBootstrapper.staggerFor(1));
        assertEquals(2000L, AgentSchedulerBootstrapper.staggerFor(2));
        assertEquals(10000L, AgentSchedulerBootstrapper.staggerFor(100),
                "定义数量多时首跑延迟必须封顶");
    }

    @Test
    void fixedRateAndDelayTranslateToFrameworkConfig() {
        ScheduleConfig rate = AgentSchedulerBootstrapper.toFrameworkConfig(
                new AgentSpec.ScheduleConfig(
                        "FIXED_RATE", null, 60000L, null, null, null, "p", "ops"),
                1000L);
        assertEquals(60000L, rate.getFixedRate());
        assertEquals("FIXED_RATE", rate.getScheduleMode().name());
        assertEquals(1000L, rate.getInitialDelay());

        ScheduleConfig delay = AgentSchedulerBootstrapper.toFrameworkConfig(
                new AgentSpec.ScheduleConfig(
                        "FIXED_DELAY", null, null, 60000L, null, null, "p", "ops"),
                1500L);
        assertEquals(60000L, delay.getFixedDelay());
        assertEquals("FIXED_DELAY", delay.getScheduleMode().name());
        assertEquals(1500L, delay.getInitialDelay());
    }

    @Test
    void explicitInitialDelayIsNeverOverwrittenByStagger() {
        ScheduleConfig config = AgentSchedulerBootstrapper.toFrameworkConfig(
                new AgentSpec.ScheduleConfig(
                        "FIXED_RATE", null, 60000L, null, 0L, null, "p", "ops"),
                1000L);
        assertEquals(0L, config.getInitialDelay());
    }

    @Test
    void cronWithoutStaggerHasNoDerivedInitialDelay() {
        ScheduleConfig config = AgentSchedulerBootstrapper.toFrameworkConfig(
                new AgentSpec.ScheduleConfig(
                        "CRON", "0 0 8 * * ?", null, null, null, "UTC", "p", "ops"),
                null);
        assertNull(config.getInitialDelay(), "CRON 由表达式决定触发时机，不应派生错峰延迟");
        assertEquals("UTC", config.getZoneId());
        assertEquals("0 0 8 * * ?", config.getCronExpression());
    }
}
