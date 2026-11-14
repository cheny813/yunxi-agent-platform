package io.yunxi.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link InMemoryMetricRegistry} 行为测试：计数器初始化、自增、按名称隔离，
 * 以及仪表盘值的覆盖写入。
 *
 * <p>本类使用进程内实现，无外部依赖，属于纯单元测试。
 */
class InMemoryMetricRegistryTest {

    @Test
    void counterInitializesAtZero() {
        assertEquals(0L, new InMemoryMetricRegistry().counter("unknown"));
    }

    @Test
    void incrementCounterIncrementsFromZero() {
        InMemoryMetricRegistry reg = new InMemoryMetricRegistry();
        reg.incrementCounter("c1");
        assertEquals(1L, reg.counter("c1"));
        reg.incrementCounter("c1");
        assertEquals(2L, reg.counter("c1"));
    }

    @Test
    void incrementCounterIsolatedByName() {
        InMemoryMetricRegistry reg = new InMemoryMetricRegistry();
        reg.incrementCounter("a");
        reg.incrementCounter("b");
        reg.incrementCounter("b");
        assertEquals(1L, reg.counter("a"));
        assertEquals(2L, reg.counter("b"));
    }

    @Test
    void recordGaugeOverwrites() {
        InMemoryMetricRegistry reg = new InMemoryMetricRegistry();
        reg.recordGauge("g", 1.5);
        reg.recordGauge("g", 3.5);
        assertEquals(3.5, reg.gauge("g"));
    }

    @Test
    void gaugeUnknownReturnsNull() {
        assertEquals(null, new InMemoryMetricRegistry().gauge("missing"));
    }
}
