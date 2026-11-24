package io.yunxi.app.config;

import io.yunxi.runtime.middleware.ResilienceThresholds;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 韧性阈值配置（yunxi.resilience.*）：经 ResilienceMiddleware 在模型调用与工具执行两个热点处生效。
 *
 * <p>默认值单一来源为 {@link ResilienceThresholds#defaults()}（与外部化前的硬编码行为一致），
 * 未配置段零行为变化；调整后重启生效。
 */
@ConfigurationProperties(prefix = "yunxi.resilience")
public class ResilienceProperties {

    private static final ResilienceThresholds DEFAULTS = ResilienceThresholds.defaults();

    /** 熔断滑动窗口大小（按调用次数计）。 */
    private int slidingWindowSize = DEFAULTS.slidingWindowSize();

    /** 失败率阈值（百分比），窗口内失败率超过该值进入 OPEN。 */
    private int failureRateThreshold = DEFAULTS.failureRateThreshold();

    /** 限流：每个刷新周期内允许的调用次数。 */
    private int limitForPeriod = DEFAULTS.limitForPeriod();

    /** 限流刷新周期（秒）。 */
    private int limitRefreshSeconds = DEFAULTS.limitRefreshSeconds();

    /** 熔断 OPEN 状态的等待时长（秒），超时后转 HALF-OPEN 试探。 */
    private int waitOpenSeconds = DEFAULTS.waitOpenSeconds();

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }

    public int getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(int failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public int getLimitForPeriod() {
        return limitForPeriod;
    }

    public void setLimitForPeriod(int limitForPeriod) {
        this.limitForPeriod = limitForPeriod;
    }

    public int getLimitRefreshSeconds() {
        return limitRefreshSeconds;
    }

    public void setLimitRefreshSeconds(int limitRefreshSeconds) {
        this.limitRefreshSeconds = limitRefreshSeconds;
    }

    public int getWaitOpenSeconds() {
        return waitOpenSeconds;
    }

    public void setWaitOpenSeconds(int waitOpenSeconds) {
        this.waitOpenSeconds = waitOpenSeconds;
    }
}
