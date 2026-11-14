package io.yunxi.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.harness.agent.bus.BusEntry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Sinks;

/**
 * {@link RedisMessageBus} 真实 Redis 集成测试。
 *
 * <p>门控：本机 Redis（默认 {@code 127.0.0.1:6379}）不可达时整体跳过，绝不伪造证据。
 * 可用系统属性覆盖地址：{@code -Dredis.host=... -Dredis.port=...}。
 *
 * <p>覆盖框架 {@link MessageBus} 接口的全部消费模式：Mode A 排水队列、Mode C 重放日志、Mode D 瞬态广播，
 * 以及 inbox / session-events / wakeup 域辅助方法（default 方法基于上述原语）是否在本后端上正确工作。
 */
class RedisMessageBusRealRedisTest {

    private static final String REDIS_HOST = System.getProperty("redis.host", "127.0.0.1");
    private static final int REDIS_PORT = Integer.getInteger("redis.port", 6379);

    private LettuceConnectionFactory cf;
    private StringRedisTemplate sharedTemplate;
    private RedisMessageBus bus;
    private String prefix;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(redisAvailable(), "Redis 不可达（127.0.0.1:6379），跳过 RedisMessageBus 集成测试");
        prefix = "rmbtest:" + UUID.randomUUID() + ":";
        sharedTemplate = new StringRedisTemplate(cf);
        sharedTemplate.afterPropertiesSet();
        bus = new RedisMessageBus(sharedTemplate, prefix);
    }

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.close();
        }
        if (cf != null && cf.isRunning()) {
            cf.destroy();
        }
    }

    // ---- Mode D: transient broadcast ----

    @Test
    void modeD_publishSubscribe_roundTrip() throws Exception {
        String channel = prefix + "modeD";
        Map<String, Object> payload = Map.of("type", "REBALANCE", "shard", 3);

        Sinks.Many<Map<String, Object>> sink = Sinks.many().multicast().onBackpressureBuffer();
        bus.subscribe(channel).subscribe(sink::tryEmitNext);
        Thread.sleep(800); // 等待 Redis SUBSCRIBE 在服务端生效

        bus.publish(channel, payload).block(Duration.ofSeconds(3));

        Map<String, Object> received = sink.asFlux().blockFirst(Duration.ofSeconds(3));
        assertThat(received).isNotNull();
        assertThat(received).containsEntry("type", "REBALANCE").containsEntry("shard", 3);
    }

    // ---- Mode A: drain queue (single consumer, ack-on-read) ----

    @Test
    void modeA_queue_pushDrainPeekDelete() {
        String q = prefix + "modeA";
        assertThat(bus.queuePeek(q).block(Duration.ofSeconds(3))).isFalse();

        bus.queuePush(q, Map.of("a", 1)).block(Duration.ofSeconds(3));
        bus.queuePush(q, Map.of("a", 2)).block(Duration.ofSeconds(3));
        assertThat(bus.queuePeek(q).block(Duration.ofSeconds(3))).isTrue();

        List<BusEntry> drained = bus.queueDrain(q, 10).block(Duration.ofSeconds(3));
        assertThat(drained).hasSize(2);
        assertThat(drained.get(0).payload()).containsEntry("a", 1);
        assertThat(drained.get(1).payload()).containsEntry("a", 2);

        assertThat(bus.queuePeek(q).block(Duration.ofSeconds(3))).isFalse();
    }

    @Test
    void modeA_queue_drainRespectsMaxCount() {
        String q = prefix + "modeA2";
        for (int i = 0; i < 5; i++) {
            bus.queuePush(q, Map.of("i", i)).block(Duration.ofSeconds(3));
        }
        List<BusEntry> drained = bus.queueDrain(q, 2).block(Duration.ofSeconds(3));
        assertThat(drained).hasSize(2);
        assertThat(drained.get(0).payload()).containsEntry("i", 0);
        assertThat(drained.get(1).payload()).containsEntry("i", 1);
        // 剩余 3 条仍在队列
        assertThat(bus.queueDrain(q, 10).block(Duration.ofSeconds(3))).hasSize(3);
    }

    // ---- Mode C: replay log (multi-consumer, cursor) ----

    @Test
    void modeC_log_appendReadTrim() {
        String log = prefix + "modeC";
        String id1 = bus.logAppend(log, Map.of("e", "one"), 0).block(Duration.ofSeconds(3));
        String id2 = bus.logAppend(log, Map.of("e", "two"), 0).block(Duration.ofSeconds(3));
        assertThat(id1).isNotNull();
        assertThat(id2).isNotNull();

        List<BusEntry> all = bus.logRead(log, null, 0).block(Duration.ofSeconds(3));
        assertThat(all).hasSize(2);
        assertThat(all.get(0).entryId()).isEqualTo(id1);
        assertThat(all.get(0).payload()).containsEntry("e", "one");

        // 游标增量读取：从 id1 之后读，应只拿到 id2
        List<BusEntry> after = bus.logRead(log, id1, 0).block(Duration.ofSeconds(3));
        assertThat(after).hasSize(1);
        assertThat(after.get(0).entryId()).isEqualTo(id2);

        bus.logTrim(log).block(Duration.ofSeconds(3));
        assertThat(bus.logRead(log, null, 0).block(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void modeC_log_maxLenTrim() {
        String log = prefix + "modeC2";
        for (int i = 0; i < 10; i++) {
            bus.logAppend(log, Map.of("i", i), 3).block(Duration.ofSeconds(3));
        }
        List<BusEntry> all = bus.logRead(log, null, 0).block(Duration.ofSeconds(3));
        assertThat(all).hasSize(3); // MAXLEN 限长后只保留最近 3 条
    }

    // ---- domain helpers (default methods on the interface) ----

    @Test
    void domain_inbox_pushDrain() {
        String owner = prefix + "owner-x";
        bus.inboxPush(owner, Map.of("m", "msg-1")).block(Duration.ofSeconds(3));
        bus.inboxPush(owner, Map.of("m", "msg-2")).block(Duration.ofSeconds(3));

        List<BusEntry> drained = bus.inboxDrain(owner, 100).block(Duration.ofSeconds(3));
        assertThat(drained).hasSize(2);
        assertThat(drained.get(0).payload()).containsEntry("m", "msg-1");
        assertThat(drained.get(1).payload()).containsEntry("m", "msg-2");
    }

    @Test
    void domain_session_publishAndRead() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        bus.sessionPublishEvent(sessionId, Map.of("kind", "tool_call", "n", 1)).block(Duration.ofSeconds(3));
        bus.sessionPublishEvent(sessionId, Map.of("kind", "tool_call", "n", 2)).block(Duration.ofSeconds(3));

        List<BusEntry> events = bus.sessionReadEvents(sessionId, null, 0).block(Duration.ofSeconds(3));
        assertThat(events).hasSize(2);
        assertThat(events.get(0).payload()).containsEntry("n", 1);

        // 游标增量读取：从第一条之后读，应只拿到第二条
        String cursor = events.get(0).entryId();
        List<BusEntry> after = bus.sessionReadEvents(sessionId, cursor, 0).block(Duration.ofSeconds(3));
        assertThat(after).hasSize(1);
        assertThat(after.get(0).payload()).containsEntry("n", 2);

        // 实时订阅也能收到
        Sinks.Many<Map<String, Object>> sink = Sinks.many().multicast().onBackpressureBuffer();
        bus.sessionSubscribeEvents(sessionId).subscribe(sink::tryEmitNext);
        Thread.sleep(800);
        bus.sessionPublishEvent(sessionId, Map.of("kind", "tool_call", "n", 3)).block(Duration.ofSeconds(3));
        Map<String, Object> live = sink.asFlux().blockFirst(Duration.ofSeconds(3));
        assertThat(live).containsEntry("n", 3);
    }

    @Test
    void domain_wakeup_enqueueAndSubscribe() throws Exception {
        Sinks.Many<Map<String, Object>> sink = Sinks.many().multicast().onBackpressureBuffer();
        bus.subscribeWakeup().subscribe(sink::tryEmitNext);
        Thread.sleep(800);
        bus.enqueueWakeup("session-1", "agent-7").block(Duration.ofSeconds(3));

        // subscribeWakeup 收到的是「立即排干的信号」（空 payload），地址详情在 wakeups 队列中
        Map<String, Object> signal = sink.asFlux().blockFirst(Duration.ofSeconds(3));
        assertThat(signal).isNotNull();
    }

    // ---- gate ----

    private boolean redisAvailable() {
        LettuceConnectionFactory local = null;
        try {
            RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(REDIS_HOST, REDIS_PORT);
            String pw = System.getProperty("redis.password", "redispass");
            if (!pw.isEmpty()) {
                cfg.setPassword(pw);
            }
            local = new LettuceConnectionFactory(cfg);
            local.afterPropertiesSet();
            local.getConnection().ping();
            this.cf = local;
            return true;
        } catch (Exception e) {
            if (local != null) {
                try {
                    local.destroy();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            return false;
        }
    }
}
