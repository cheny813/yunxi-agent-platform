package io.yunxi.app.startup;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisStartupGateTest {

    @Test
    void tryAcquire_failOpen_whenRedisAbsent() {
        // Redis 不可用（ObjectProvider 恒返回 null）→ fail-open 放行，且 release 不得抛异常
        ObjectProvider<StringRedisTemplate> absent = new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() {
                return null;
            }

            @Override
            public StringRedisTemplate getObject(Object... args) {
                return null;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return null;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return null;
            }
        };
        RedisStartupGate gate = new RedisStartupGate(absent, "yunxi:startup-gate:leader", 1, java.time.Duration.ofSeconds(30));
        assertTrue(gate.tryAcquire(), "Redis 不可用时必须 fail-open 放行");
        gate.release();
    }
}
