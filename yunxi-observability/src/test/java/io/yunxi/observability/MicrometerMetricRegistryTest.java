package io.yunxi.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * {@link MicrometerMetricRegistry} 行为测试：与 {@link MetricRegistry} 契约语义等价
 * （计数器从零累加、仪表盘覆盖同名旧值、名称隔离），且度量真实进入 Micrometer registry 可被导出。
 */
class MicrometerMetricRegistryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MicrometerMetricRegistry registry = new MicrometerMetricRegistry(meters);

    @Test
    void counterAccumulatesIntoMicrometerRegistry() {
        registry.incrementCounter("agent.invocations");
        registry.incrementCounter("agent.invocations");

        assertEquals(2.0d, meters.get("agent.invocations").counter().count());
    }

    @Test
    void counterIncrementsFromZeroWithoutCreatingUnrelatedMeters() {
        registry.incrementCounter("model.calls");

        assertEquals(1.0d, meters.get("model.calls").counter().count());
        assertEquals(1, meters.getMeters().size(), "仅被递增的名称注册 meter，不应产生其它度量");
    }

    @Test
    void gaugeOverwritesPreviousValue() {
        registry.recordGauge("yunxi.intent.latency.ms", 12.5d);
        registry.recordGauge("yunxi.intent.latency.ms", 3.0d);

        assertEquals(3.0d, meters.get("yunxi.intent.latency.ms").gauge().value());
    }

    @Test
    void gaugeTracksLatestValueAcrossRegistrations() {
        registry.recordGauge("queue.depth", 1.0d);
        registry.recordGauge("queue.depth", 9.0d);

        assertEquals(9.0d, meters.get("queue.depth").gauge().value());
        assertEquals(1, meters.getMeters().stream()
                .filter(m -> m.getId().getName().equals("queue.depth")).count(),
                "同名仪表盘只允许注册一次，后续写入复用同一 gauge");
    }

    @Test
    void rejectsNullMeterRegistry() {
        assertThrows(NullPointerException.class, () -> new MicrometerMetricRegistry(null));
    }

    @Test
    void gaugeIsReadableThroughMicrometer() {
        registry.recordGauge("tenant.active", 42.0d);

        assertNotNull(meters.get("tenant.active").gauge());
        assertEquals(42.0d, meters.get("tenant.active").gauge().value());
    }
}
