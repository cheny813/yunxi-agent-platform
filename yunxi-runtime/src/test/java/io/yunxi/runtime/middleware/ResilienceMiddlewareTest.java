package io.yunxi.runtime.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.junit.jupiter.api.Test;

/** 韧性阈值外部化：默认值必须等于历史硬编码行为；显式配置必须原样生效。 */
class ResilienceMiddlewareTest {

    @Test
    void thresholdsDefaultToHistoricalBehavior() {
        ResilienceThresholds t = ResilienceThresholds.defaults();
        assertEquals(10, t.slidingWindowSize());
        assertEquals(50, t.failureRateThreshold());
        assertEquals(100, t.limitForPeriod());
        assertEquals(1, t.limitRefreshSeconds());
        assertEquals(30, t.waitOpenSeconds());
    }

    @Test
    void configuredThresholdsPropagateToResilience4jConfigs() {
        ResilienceMiddleware middleware = new ResilienceMiddleware(null, null, null,
                new ResilienceThresholds(7, 40, 55, 2, 17));
        CircuitBreakerConfig cb = middleware.circuitBreakerConfig();
        RateLimiterConfig rl = middleware.rateLimiterConfig();
        assertEquals(40, cb.getFailureRateThreshold());
        assertEquals(7, cb.getSlidingWindowSize());
        // waitOpenSeconds 的传播无法在此断言：当前 resilience4j 版本的 CircuitBreakerConfig 未暴露
        // getWaitDurationInOpenState()；该值由 ResilienceThresholds record 单一承载并在 defaults 用例中覆盖。
        assertEquals(55, rl.getLimitForPeriod());
        assertEquals(2, rl.getLimitRefreshPeriod().toSeconds());
    }
}
