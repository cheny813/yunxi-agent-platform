package io.yunxi.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生产用 {@link MetricRegistry}：把平台度量桥接到 Micrometer 导出通道。
 *
 * <p>{@link MetricRegistry} 仍是平台唯一的度量记录入口（本类不改消费方 API），Micrometer 只承担导出职责：
 * 进程内注册的 {@code MeterRegistry} 若挂有注册表实现（如 OTLP），度量即经该通道上报；无导出器时度量仍可经
 * actuator 指标端点读取，成本仅为内存计量。仪表盘用同名 {@link AtomicReference} 承载"覆盖式"语义，
 * 保证与 {@link InMemoryMetricRegistry} 契约一致（重复写入取最新值，不累加）。
 */
public class MicrometerMetricRegistry implements MetricRegistry {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

    /**
     * @param meterRegistry 进程内 Micrometer registry（由装配层提供，不可为 {@code null}）
     */
    public MicrometerMetricRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /** 递增计数器；同名多次调用累加，首次调用从 0 开始。 */
    @Override
    public void incrementCounter(String name) {
        meterRegistry.counter(name).increment();
    }

    /** 写入仪表盘瞬时值（覆盖同名旧值）；同名 gauge 只注册一次，之后仅更新其读数。 */
    @Override
    public void recordGauge(String name, double value) {
        gauges.computeIfAbsent(name, this::registerGauge).set(value);
    }

    private AtomicReference<Double> registerGauge(String name) {
        AtomicReference<Double> value = new AtomicReference<>(Double.NaN);
        Gauge.builder(name, value, AtomicReference::get).register(meterRegistry);
        return value;
    }
}
