package io.yunxi.platform.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.yunxi.platform.trace.RedisTraceStore;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import io.agentscope.core.message.ToolResultState;

/**
 * 跨实例轨迹共享演练（V10-38 待交付项的存储层证明）。
 *
 * <p>证明：两个独立 Redis 连接（模拟两个应用实例）指向同一 Redis 时，实例 A {@code append} 的轨迹
 * 能被实例 B {@code read} 到 —— 即负载均衡下「谁写的都能被谁读到」，多实例部署轨迹不丢。</p>
 *
 * <p>门禁：仅当本机 Redis（localhost:6379，密码 redispass，见 docker-compose.yml）可达时运行；
 * 不可达时整类跳过（{@code assumeTrue}），不污染单元回归。真实多实例 + 断点续传端到端演练
 * 仍需要 docker-compose.multi-instance.yml + 人工按 docs/breakpoint-resume-drill.md 执行。</p>
 *
 * @author yunxi-agent-platform
 */
class RedisTraceStoreCrossInstanceTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;
    private static final String PASSWORD = "redispass";

    private static RedisConnectionFactory factoryA;
    private static RedisConnectionFactory factoryB;
    private static RedisTemplate<String, Object> templateA;
    private static RedisTemplate<String, Object> templateB;
    private static boolean reachable;

    @BeforeAll
    static void setUp() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(HOST, PORT), 800);
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        assumeTrue(reachable, "本机 Redis 不可达（localhost:6379），跳过跨实例演练；启动 docker compose 后重跑");

        factoryA = newFactory();
        factoryB = newFactory();
        templateA = newTemplate(factoryA);
        templateB = newTemplate(factoryB);
        // 清空命名空间，避免与历史数据串扰
        templateA.delete(templateA.keys("yunxi:trace:*"));
    }

    @AfterAll
    static void tearDown() {
        if (templateA != null) {
            templateA.delete(templateA.keys("yunxi:trace:*"));
        }
        if (factoryA instanceof LettuceConnectionFactory f) {
            f.destroy();
        }
        if (factoryB instanceof LettuceConnectionFactory f) {
            f.destroy();
        }
    }

    private static RedisConnectionFactory newFactory() {
        LettuceConnectionFactory f = new LettuceConnectionFactory(HOST, PORT);
        f.setPassword(PASSWORD);
        f.afterPropertiesSet();
        return f;
    }

    private static RedisTemplate<String, Object> newTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(factory);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        t.setHashKeySerializer(new StringRedisSerializer());
        t.afterPropertiesSet();
        return t;
    }

    @Test
    void instanceAWriteVisibleToInstanceB() {
        RedisTraceStore storeA = new RedisTraceStore(templateA);
        RedisTraceStore storeB = new RedisTraceStore(templateB);

        String traceId = "cross-inst:" + System.nanoTime();
        ReasoningSpan turn = ReasoningSpan.open(traceId, "TURN|-|TURN|r1", "e1", SpanKind.TURN, null);
        ReasoningSpan text = ReasoningSpan.delta(traceId, "TEXT|-|BLOCK_ID|m1", "e2", SpanKind.TEXT, "hi");
        ReasoningSpan end = ReasoningSpan.closed(traceId, "TURN|-|TURN|r1", "e3", SpanKind.TURN, ToolResultState.SUCCESS, 5L, Map.of());

        storeA.append(turn);
        storeA.append(text);
        storeA.append(end);

        // 由实例 B 读取 —— 跨实例可见
        List<ReasoningSpan> fromB = storeB.read(traceId);
        assertFalse(fromB.isEmpty(), "实例 B 应能看到实例 A 写入的轨迹");
        assertEquals(3, fromB.size(), "跨实例读取应返回全部 3 个快照");
        assertEquals(SpanKind.TEXT, fromB.get(1).kind());
        assertEquals("hi", fromB.get(1).delta());

        storeA.clear(traceId);
        assertEquals(0, storeB.read(traceId).size(), "清理后实例 B 亦不可见");
    }
}
