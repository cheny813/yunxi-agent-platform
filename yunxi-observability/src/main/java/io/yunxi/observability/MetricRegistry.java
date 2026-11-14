package io.yunxi.observability;

/**
 * 可观测平面。最小度量面：计数器与仪表盘。运行时与织面在此记录；生产装配可桥接到 Micrometer / Prometheus。
 */
public interface MetricRegistry {

    /** 递增指定名称的计数器（名称不存在时初始化为 0 后再 +1）。 */
    void incrementCounter(String name);

    /** 记录指定名称的仪表盘瞬时值（覆盖同名旧值）。 */
    void recordGauge(String name, double value);
}
