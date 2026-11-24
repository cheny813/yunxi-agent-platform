package io.yunxi.runtime.middleware;

/**
 * 韧性阈值（限流 + 熔断），外部化配置的载体：由 yunxi-app 的 ResilienceProperties 绑定后传入。
 *
 * <p>默认值 {@link #defaults()} 即历史硬编码行为（窗口 10 / 失败率 50% / 限流 100 每秒 / OPEN 等待 30 秒），
 * 未配置时行为不变。
 */
public record ResilienceThresholds(
        int slidingWindowSize,
        int failureRateThreshold,
        int limitForPeriod,
        int limitRefreshSeconds,
        int waitOpenSeconds) {

    /** 历史默认行为（与外部化前硬编码一致）。 */
    public static ResilienceThresholds defaults() {
        return new ResilienceThresholds(10, 50, 100, 1, 30);
    }
}
