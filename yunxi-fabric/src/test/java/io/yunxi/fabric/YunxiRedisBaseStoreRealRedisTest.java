package io.yunxi.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnLease;
import io.yunxi.runtime.DistributedRuntimeConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 在真实 Redis 上验证分布式共享区与会话状态后端的存储语义。
 *
 * <p>默认不运行：外部中间件不应成为单元测试套件的前置依赖。显式开启后，连接参数取自
 * {@code REDIS_HOST / REDIS_PORT / REDIS_PASSWORD} 系统属性或同名环境变量；口令须显式提供，
 * 未设置时按空口令连接。每次运行使用独立的随机键前缀并在结束后清理，不污染共享实例。</p>
 *
 * <p>运行方式（{@code -am} 会让 {@code -Dtest} 作用于上游模块，须关闭无匹配测试即失败）：</p>
 * <pre>
 *   mvn -o -pl yunxi-fabric -am test -Dyunxi.redis.it=true -DREDIS_PASSWORD=&lt;password&gt; \
 *       -Dtest=YunxiRedisBaseStoreRealRedisTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>重点覆盖内存替身无法证明的行为：Lua 脚本在真实 Redis 上的返回值类型、含分隔符的键、
 * 索引集合的字典序扫描与分页，以及并发写下的乐观锁原子性。</p>
 */
@EnabledIfSystemProperty(named = "yunxi.redis.it", matches = "true")
class YunxiRedisBaseStoreRealRedisTest {

    private static final List<String> NS = List.of("agent", "it-session");

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate template;
    private String keyPrefix;
    private YunxiRedisBaseStore store;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        String host = property("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(property("REDIS_PORT", "6379"));
        String password = property("REDIS_PASSWORD", "");

        connectionFactory = new LettuceConnectionFactory(host, port);
        if (!password.isBlank()) {
            connectionFactory.setPassword(password);
        }
        connectionFactory.afterPropertiesSet();

        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        RedisConnection connection = connectionFactory.getConnection();
        try {
            assertEquals("PONG", connection.ping(), "real Redis must be reachable and authenticated");
        } finally {
            connection.close();
        }

        keyPrefix = "yunxi:it:" + UUID.randomUUID() + ":";
        store = new YunxiRedisBaseStore(template, keyPrefix);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // 先停掉所有并发任务，避免异常路径下残留线程在键清理之后继续写入共享实例。
        if (pool != null) {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "writer pool must terminate");
            pool = null;
        }
        if (template != null && keyPrefix != null) {
            Set<String> keys = template.keys(keyPrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                template.delete(keys);
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void putThenGetRoundTripsValueAndVersion() {
        store.put(NS, "chunk-a", Map.of("k", "v", "n", 7));

        StoreItem item = store.get(NS, "chunk-a");
        assertNotNull(item);
        assertEquals("chunk-a", item.key());
        assertEquals(1L, item.version());
        assertEquals("v", item.value().get("k"));
        assertEquals(7, ((Number) item.value().get("n")).intValue());
    }

    @Test
    void putIfVersionSucceedsOnMatchAndBumpsVersion() {
        store.put(NS, "counter", Map.of("n", 1));

        assertTrue(store.putIfVersion(NS, "counter", Map.of("n", 2), 1L));
        assertEquals(2L, store.get(NS, "counter").version());
        assertEquals(2, ((Number) store.get(NS, "counter").value().get("n")).intValue());
    }

    @Test
    void putIfVersionRejectsStaleVersionAndKeepsItem() {
        store.put(NS, "counter", Map.of("n", 1));

        assertFalse(store.putIfVersion(NS, "counter", Map.of("n", 9), 5L));
        assertEquals(1L, store.get(NS, "counter").version());
        assertEquals(1, ((Number) store.get(NS, "counter").value().get("n")).intValue());
    }

    @Test
    void putIfVersionZeroCreatesIfAbsent() {
        assertTrue(store.putIfVersion(NS, "fresh", Map.of("n", 1), 0L));
        assertEquals(1L, store.get(NS, "fresh").version());
        assertFalse(store.putIfVersion(NS, "fresh", Map.of("n", 2), 0L));
        assertEquals(1L, store.get(NS, "fresh").version());
    }

    @Test
    void putIfVersionAllowsOnlyOneWinnerWhenWritersShareExpectedVersion() throws Exception {
        store.put(NS, "counter", Map.of("n", 0));

        int writers = 12;
        pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int seq = i;
            futures.add(
                    pool.submit(
                            () -> {
                                start.await();
                                return store.putIfVersion(NS, "counter", Map.of("n", seq), 1L);
                            }));
        }
        start.countDown();

        long winners = 0;
        for (Future<Boolean> future : futures) {
            if (Boolean.TRUE.equals(future.get(20, TimeUnit.SECONDS))) {
                winners++;
            }
        }

        assertEquals(1L, winners, "Lua CAS must let exactly one writer win at a given version");
        assertEquals(2L, store.get(NS, "counter").version());
    }

    @Test
    void noLostUpdatesUnderConcurrentCasRetries() throws Exception {
        store.put(NS, "counter", Map.of("n", 0));
        int writers = 6;
        int increments = 8;

        pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            futures.add(
                    pool.submit(
                            () -> {
                                start.await();
                                for (int j = 0; j < increments; j++) {
                                    for (int attempt = 0; attempt < 1000; attempt++) {
                                        StoreItem cur = store.get(NS, "counter");
                                        int n = ((Number) cur.value().get("n")).intValue();
                                        if (store.putIfVersion(
                                                NS, "counter", Map.of("n", n + 1), cur.version())) {
                                            break;
                                        }
                                        if (attempt == 999) {
                                            fail("CAS never succeeded within 1000 retries");
                                        }
                                    }
                                }
                                return null;
                            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }

        StoreItem finalItem = store.get(NS, "counter");
        assertEquals(
                writers * increments,
                ((Number) finalItem.value().get("n")).intValue(),
                "every successful CAS must contribute exactly one increment");
        assertEquals((long) writers * increments + 1L, finalItem.version());
    }

    @Test
    void searchHonoursLexicographicOrderAndPaging() {
        // 插入顺序与字典序相反：c10 在字典序上先于 c2。
        store.put(NS, "c2", Map.of("x", 2));
        store.put(NS, "c10", Map.of("x", 10));

        assertEquals(List.of("c10", "c2"), keysOf(store.search(NS, 10, 0)));
        assertEquals(List.of("c10"), keysOf(store.search(NS, 1, 0)));
        assertEquals(List.of("c2"), keysOf(store.search(NS, 1, 1)));
        assertEquals(List.of(), keysOf(store.search(NS, 10, 5)));
    }

    @Test
    void deleteRemovesIndexMemberAndIsIdempotent() {
        store.put(NS, "c1", Map.of("x", 1));
        store.put(NS, "c2", Map.of("x", 2));

        store.delete(NS, "c1");
        assertNull(store.get(NS, "c1"));
        assertEquals(List.of("c2"), keysOf(store.search(NS, 10, 0)));

        // 直接检查索引集合，避免 stale index 被 search 的容错分支掩盖。
        Set<String> idxKeys = template.keys(keyPrefix + "idx:*");
        assertNotNull(idxKeys);
        assertEquals(1, idxKeys.size());
        Set<String> members = template.opsForZSet().range(idxKeys.iterator().next(), 0, -1);
        assertEquals(Set.of("c2"), members, "DELETE script must ZREM the index member");

        store.delete(NS, "c1");
        assertNull(store.get(NS, "c1"));
        assertEquals(List.of("c2"), keysOf(store.search(NS, 10, 0)));
    }

    @Test
    void distributedRuntimeConfigPerformsRealIoOnRealLettuceClient() throws Exception {
        DistributedRuntimeConfig config = new RedisDistributedRuntimeConfig(template, keyPrefix);

        BaseStore chunks = config.sharedChunkStore();
        assertInstanceOf(YunxiRedisBaseStore.class, chunks);
        chunks.put(NS, "via-config", Map.of("ok", true));
        assertEquals(1L, chunks.get(NS, "via-config").version());
        assertEquals(Boolean.TRUE, chunks.get(NS, "via-config").value().get("ok"));

        SessionTurnGate gate = config.sessionTurnGate();
        assertNotNull(gate);
        TurnLease lease = gate.acquire("it-turn");
        try {
            assertTrue(gate.isRunning("it-turn"));
        } finally {
            lease.close();
        }
        assertFalse(gate.isRunning("it-turn"));

        assertInstanceOf(RedisSessionTurnGate.class, gate).shutdown();
    }

    private static List<String> keysOf(List<StoreItem> items) {
        return items.stream().map(StoreItem::key).toList();
    }

    private static String property(String name, String fallback) {
        String fromSystem = System.getProperty(name);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        String fromEnv = System.getenv(name);
        return (fromEnv == null || fromEnv.isBlank()) ? fallback : fromEnv;
    }
}
