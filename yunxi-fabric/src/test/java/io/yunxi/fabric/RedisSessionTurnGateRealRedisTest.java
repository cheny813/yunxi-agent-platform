package io.yunxi.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RedisSessionTurnGate} 在真实 Redis 上的租约归属语义：续租与释放都必须以「仍持有该锁」为前提，
 * 否则多副本下会出现两个实例同时持锁（MUSE 技能演进的单写者保证随之失效）。
 *
 * <p>仅当本机 Redis 可用时运行（默认 127.0.0.1:6379，密码 {@code redispass}；可用
 * {@code -Dredis.host} / {@code -Dredis.port} / {@code -Dredis.password} 覆盖）。不可用时跳过，不计入失败。</p>
 */
class RedisSessionTurnGateRealRedisTest {

    private static final String HOST = System.getProperty("redis.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("redis.port", "6379"));
    private static final String PASSWORD = System.getProperty("redis.password", "redispass");

    private static final String PREFIX = "yunxi:test:turn-gate:";
    private static final String SKILL_KEY = "muse:skill:default:demo";

    /** 缩短租约与续租周期，使「租约过期 → 他人接管 → 原持有者续租」在秒级内可复现。 */
    private static final Duration LEASE_TTL = Duration.ofSeconds(5);
    private static final Duration RENEW_INTERVAL = Duration.ofMillis(100);

    /**
     * 接管者副本的续租周期刻意拉长：否则两个副本都以 100ms 续租会互相覆盖，
     * 断言只能观测到时序噪声而无法判定归属。接管者不续租时，锁内 token 一旦被改写就保持改写。
     */
    private static final Duration IDLE_RENEW_INTERVAL = Duration.ofSeconds(10);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    private RedisSessionTurnGate gateA;
    private RedisSessionTurnGate gateB;

    @BeforeAll
    static void connect() {
        Assumptions.assumeTrue(available(), "local Redis unavailable, skip");
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(HOST, PORT);
        configuration.setPassword(PASSWORD);
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        cleanKeys();
        gateA = new RedisSessionTurnGate(template, PREFIX, LEASE_TTL, RENEW_INTERVAL);
        gateB = new RedisSessionTurnGate(template, PREFIX, LEASE_TTL, IDLE_RENEW_INTERVAL);
    }

    @AfterEach
    void tearDown() {
        cleanKeys();
        gateA.shutdown();
        gateB.shutdown();
    }

    @Test
    @DisplayName("原持有者租约过期后不得续租他人已接管的锁")
    void renewalDoesNotStealLockOwnedByAnotherReplica() throws Exception {
        String lockKey = PREFIX + "turn:" + SKILL_KEY;
        try (TurnLease leaseA = gateA.acquire(SKILL_KEY)) {
            String tokenA = template.opsForValue().get(lockKey);
            assertNotNull(tokenA, "A 抢锁成功后键内必须有本租约的 token");

            // 模拟 A 的租约因 Redis 抖动而自然过期：键消失，B 合法接管。
            template.delete(lockKey);
            TurnLease leaseB = gateB.acquire(SKILL_KEY);
            String tokenB = template.opsForValue().get(lockKey);
            assertNotEquals(tokenA, tokenB, "接管者必须写入自己的 token");

            Thread.sleep(RENEW_INTERVAL.toMillis() * 4);

            assertEquals(tokenB, template.opsForValue().get(lockKey),
                    "过期租约的续租不得改写仍由 B 持有的锁，否则两个副本会同时认为自己是写者");
            assertTrue(template.getExpire(lockKey) > 0, "B 的锁必须仍有租期，未被 A 的续租语义破坏");

            leaseB.close();
            assertFalse(template.hasKey(lockKey), "B 释放自己的锁必须成功");
        }
    }

    @Test
    @DisplayName("释放时 token 不匹配不得删除他人锁")
    void closingStaleLeaseDoesNotDeleteForeignLock() throws Exception {
        String lockKey = PREFIX + "turn:" + SKILL_KEY;
        try (TurnLease leaseA = gateA.acquire(SKILL_KEY)) {
            template.delete(lockKey);
            TurnLease leaseB = gateB.acquire(SKILL_KEY);
            String tokenB = template.opsForValue().get(lockKey);

            leaseA.close();

            assertEquals(tokenB, template.opsForValue().get(lockKey),
                    "过期租约的释放不得删除已由他副本接管的锁");
            leaseB.close();
        }
    }

    @Test
    @DisplayName("同一把锁被占用时第二个副本在超时后拒绝，而不是并行写入")
    void concurrentAcquireIsRejectedAfterTimeout() throws Exception {
        try (TurnLease ignored = gateA.acquire(SKILL_KEY)) {
            long startedAt = System.nanoTime();
            org.junit.jupiter.api.Assertions.assertThrows(
                    TurnBusyException.class, () -> gateB.acquire(SKILL_KEY),
                    "另一副本持锁时必须拒绝，而非双跑");
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            assertTrue(elapsedMillis >= 2_000L,
                    "拒绝必须发生在抢占超时之后，实际仅等待 " + elapsedMillis + "ms");
        }
    }

    private static void cleanKeys() {
        Set<String> keys = template.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    private static boolean available() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(HOST, PORT);
        configuration.setPassword(PASSWORD);
        LettuceConnectionFactory probe = new LettuceConnectionFactory(configuration);
        probe.afterPropertiesSet();
        try {
            probe.getConnection().close();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            probe.destroy();
        }
    }
}
