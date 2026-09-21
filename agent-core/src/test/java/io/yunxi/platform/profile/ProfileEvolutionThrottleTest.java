package io.yunxi.platform.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import io.yunxi.platform.profile.config.ProfileProperties;

/**
 * ProfileEvolutionThrottle：时间 / 轮次「或」关系节流测试（内存回退路径）。
 */
@DisplayName("画像演进节流器")
class ProfileEvolutionThrottleTest {

    @SuppressWarnings("unchecked")
    private static ProfileEvolutionThrottle throttleWith(ProfileProperties props) {
        ObjectProvider<RedisConnectionFactory> noRedis = mock(ObjectProvider.class);
        when(noRedis.getIfAvailable()).thenReturn(null);
        return new ProfileEvolutionThrottle(props, noRedis);
    }

    private static ProfileProperties props(boolean enabled, long intervalSeconds, int minTurns) {
        ProfileProperties p = new ProfileProperties();
        p.getEvolution().setEnabled(enabled);
        p.getEvolution().setMinIntervalSeconds(intervalSeconds);
        p.getEvolution().setMinTurns(minTurns);
        return p;
    }

    @Test
    @DisplayName("关闭：一律不放行")
    void disabledNeverAcquires() {
        ProfileEvolutionThrottle throttle = throttleWith(props(false, 0, 0));

        assertThat(throttle.tryAcquire("u1")).isFalse();
        assertThat(throttle.tryAcquire("u1")).isFalse();
    }

    @Test
    @DisplayName("双零阈值：每轮都放行（无节流）")
    void zeroThresholdsAlwaysAcquires() {
        ProfileEvolutionThrottle throttle = throttleWith(props(true, 0, 0));

        assertThat(throttle.tryAcquire("u1")).isTrue();
        assertThat(throttle.tryAcquire("u1")).isTrue();
    }

    @Test
    @DisplayName("按轮次节流：累积到阈值才放行，放行后计数归零")
    void turnBasedThrottle() {
        ProfileEvolutionThrottle throttle = throttleWith(props(true, 0, 3));

        assertThat(throttle.tryAcquire("u1")).isFalse();
        assertThat(throttle.tryAcquire("u1")).isFalse();
        assertThat(throttle.tryAcquire("u1")).isTrue();
        // 放行后计数归零，重新累积
        assertThat(throttle.tryAcquire("u1")).isFalse();
        assertThat(throttle.tryAcquire("u1")).isFalse();
        assertThat(throttle.tryAcquire("u1")).isTrue();
    }

    @Test
    @DisplayName("按时间节流：首次放行，间隔内不放行")
    void timeBasedThrottleFirstAcquiresThenBlocks() {
        ProfileEvolutionThrottle throttle = throttleWith(props(true, 3600, 0));

        // 首次（lastAt=0）放行
        assertThat(throttle.tryAcquire("u1")).isTrue();
        // 间隔内被节流
        assertThat(throttle.tryAcquire("u1")).isFalse();
        assertThat(throttle.tryAcquire("u1")).isFalse();
    }

    @Test
    @DisplayName("节流按 userId 隔离，互不影响")
    void throttleIsPerUser() {
        ProfileEvolutionThrottle throttle = throttleWith(props(true, 3600, 0));

        assertThat(throttle.tryAcquire("u1")).isTrue();
        assertThat(throttle.tryAcquire("u1")).isFalse();
        // 另一个用户不受 u1 的节流状态影响
        assertThat(throttle.tryAcquire("u2")).isTrue();
    }

    @Test
    @DisplayName("userId 为空：不放行")
    void blankUserDoesNotAcquire() {
        ProfileEvolutionThrottle throttle = throttleWith(props(true, 0, 0));

        assertThat(throttle.tryAcquire(null)).isFalse();
        assertThat(throttle.tryAcquire("  ")).isFalse();
    }

    @Test
    @DisplayName("时间与轮次为「或」关系：轮次先达成即放行")
    void timeOrTurnsWhicheverFirst() {
        // 不按时间节流（阈值 0），仅轮次阈值 2 → 第二轮回合即放行
        ProfileEvolutionThrottle throttle = throttleWith(props(true, 0, 2));

        assertThat(throttle.tryAcquire("u1")).isFalse();
        assertThat(throttle.tryAcquire("u1")).isTrue();
    }

    @Test
    @DisplayName("首次调用必然放行：新用户首轮即抽取（lastAt=0 视为已超间隔）")
    void firstCallAlwaysAcquires() {
        ProfileEvolutionThrottle throttle = throttleWith(props(true, 3600, 3));

        assertThat(throttle.tryAcquire("fresh-user")).isTrue();
    }
}
