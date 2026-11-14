package io.yunxi.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 定义平面验证：调度段是纯数据，YAML 与 record 形态一致，且新增字段不破坏既有定义文件。
 */
class AgentSpecScheduleConfigTest {

    private final YamlSpecCodec codec = new YamlSpecCodec();

    @Test
    void scheduleIsPureDataRecord() {
        AgentSpec.ScheduleConfig schedule = new AgentSpec.ScheduleConfig(
                "CRON", "0 0 8 * * ?", null, null, 1000L, "Asia/Shanghai", "daily", "ops");

        assertEquals("CRON", schedule.mode());
        assertEquals("0 0 8 * * ?", schedule.cron());
        assertNull(schedule.rateMillis());
        assertNull(schedule.delayMillis());
        assertEquals(1000L, schedule.initialDelayMillis());
        assertEquals("Asia/Shanghai", schedule.zoneId());
        assertEquals("daily", schedule.prompt());
        assertEquals("ops", schedule.triggerUserId());
    }

    @Test
    void legacyEightArgConstructorLeavesScheduleAbsent() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("a", "n", "d"),
                null, null, null, null, null,
                new AgentSpec.SubagentsConfig(List.of()), null);

        assertNull(spec.schedule(), "既有定义文件不声明 schedule 时必须为 null，保持零迁移");
    }

    @Test
    void yamlDecodeMapsScheduleFields() {
        String yaml = """
                identity:
                  agentId: agent-sched
                  name: Scheduled Agent
                schedule:
                  mode: FIXED_RATE
                  rateMillis: 60000
                  initialDelayMillis: 5000
                  prompt: daily report
                  triggerUserId: ops
                """;

        AgentSpec spec = codec.decode(yaml);
        assertNotNull(spec);
        assertNotNull(spec.schedule());
        assertEquals("FIXED_RATE", spec.schedule().mode());
        assertEquals(60000L, spec.schedule().rateMillis());
        assertEquals(5000L, spec.schedule().initialDelayMillis());
        assertEquals("daily report", spec.schedule().prompt());
        assertEquals("ops", spec.schedule().triggerUserId());
    }

    @Test
    void roundTripPreservesSchedule() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-sched", "Scheduled Agent", "d"),
                null, null, null, null, null,
                new AgentSpec.SubagentsConfig(List.of()), null,
                new AgentSpec.ScheduleConfig(
                        "CRON", "0 0 8 * * ?", null, null, 1000L, "UTC", "daily", "ops"));

        AgentSpec back = codec.decode(codec.encode(spec));
        assertEquals(spec, back, "encode → decode 往返必须保持调度段相等");
    }
}
