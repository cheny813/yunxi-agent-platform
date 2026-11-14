package io.yunxi.observability;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试与演示用的内存版 {@link MetricRegistry}。
 */
public class InMemoryMetricRegistry implements MetricRegistry {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> gauges = new ConcurrentHashMap<>();

    /** 原子递增计数器；名称不存在时初始化为 0。 */
    @Override
    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    /** 写入仪表盘瞬时值（覆盖同名的旧值）。 */
    @Override
    public void recordGauge(String name, double value) {
        gauges.put(name, value);
    }

    /** 读取计数器当前值；名称不存在时返回 0。 */
    public long counter(String name) {
        AtomicLong value = counters.get(name);
        return value == null ? 0L : value.get();
    }

    /** 读取仪表盘当前值；名称不存在时返回 {@code null}。 */
    public Double gauge(String name) {
        return gauges.get(name);
    }
}
