package io.yunxi.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.Disposable;

/**
 * {@link CrossInstanceEventBus} 真实 Redis 端到端测试（pub/sub 全链路：序列化 → Redis 扇出 → 反序列化 → 路由）。
 *
 * <p>仅当本机 Redis 可用时运行（默认 127.0.0.1:6379，密码 {@code redispass}；可用
 * {@code -Dredis.host} / {@code -Dredis.port} / {@code -Dredis.password} 覆盖）。不可用时 assumeTrue 跳过，
 * 不计入失败。
 */
class CrossInstanceEventBusRealRedisTest {

    private static final String HOST = System.getProperty("redis.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("redis.port", "6379"));
    private static final String PASSWORD = System.getProperty("redis.password", "redispass");

    private static LettuceConnectionFactory cf;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(redisAvailable(), "local Redis unavailable, skip");
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(HOST, PORT);
        config.setPassword(PASSWORD);
        cf = new LettuceConnectionFactory(config);
        cf.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (cf != null) {
            cf.destroy();
        }
    }

    private static boolean redisAvailable() {
        RedisURI uri = RedisURI.builder().withHost(HOST).withPort(PORT).withPassword(PASSWORD.toCharArray()).build();
        try (RedisClient client = RedisClient.create(uri);
                StatefulRedisConnection<String, String> conn = client.connect()) {
            conn.sync().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void publish_and_subscribe_roundTrip() throws Exception {
        StringRedisTemplate sharedTemplate = new StringRedisTemplate(cf);
        sharedTemplate.afterPropertiesSet();

        CrossInstanceEventBus publisher = new CrossInstanceEventBus(new RedisMessageBus(sharedTemplate, "yunxi:"));
        CrossInstanceEventBus subscriber = new CrossInstanceEventBus(new RedisMessageBus(sharedTemplate, "yunxi:"));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CrossInstanceEvent> received = new AtomicReference<>();
        Disposable d =
                subscriber.subscribe(CrossInstanceEventType.AGENT_DEF_CHANGED, e -> {
                    received.set(e);
                    latch.countDown();
                });

        // 等待订阅在 Redis 侧真正建立（reactive pub/sub 异步 attach）。
        Thread.sleep(500);

        publisher.publish(CrossInstanceEvent.of(
                CrossInstanceEventType.AGENT_DEF_CHANGED,
                Map.of("agentId", "a1", "scope", "agents"),
                "instance-1"));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "event should be received within 5s");
        CrossInstanceEvent e = received.get();
        assertNotNull(e);
        assertEquals(CrossInstanceEventType.AGENT_DEF_CHANGED, e.getType());
        assertEquals("a1", e.getPayload().get("agentId"));
        assertEquals("agents", e.getPayload().get("scope"));
        assertEquals("instance-1", e.getSourceInstanceKey());

        d.dispose();
    }

    @Test
    void publish_toDifferentType_notReceivedByOtherSubscriber() throws Exception {
        StringRedisTemplate sharedTemplate = new StringRedisTemplate(cf);
        sharedTemplate.afterPropertiesSet();

        CrossInstanceEventBus publisher = new CrossInstanceEventBus(new RedisMessageBus(sharedTemplate, "yunxi:"));
        CrossInstanceEventBus subscriber = new CrossInstanceEventBus(new RedisMessageBus(sharedTemplate, "yunxi:"));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CrossInstanceEvent> received = new AtomicReference<>();
        Disposable d =
                subscriber.subscribe(CrossInstanceEventType.AGENT_DEF_CHANGED, e -> {
                    received.set(e);
                    latch.countDown();
                });

        Thread.sleep(500);
        // 发布到 TENANT_CONFIG_CHANGED，AGENT_DEF_CHANGED 订阅者不应收到。
        publisher.publish(CrossInstanceEvent.of(
                CrossInstanceEventType.TENANT_CONFIG_CHANGED, Map.of("tenant", "t1"), "instance-1"));

        // 等一小段时间确认不会收到（若误收，latch 会归零）
        Thread.sleep(800);
        assertEquals(1, latch.getCount(), "subscriber of AGENT_DEF_CHANGED must NOT receive TENANT_CONFIG_CHANGED");
        d.dispose();
    }
}
