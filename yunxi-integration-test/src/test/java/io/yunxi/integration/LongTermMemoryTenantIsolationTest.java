package io.yunxi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.yunxi.fabric.RedisDistributedRuntimeConfig;
import io.yunxi.runtime.tenant.TenantAwareDistributedStore;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 在真实 Redis 上验证长期记忆（工作区 MEMORY.md 走框架 BaseStore）的跨租户键空间隔离。
 *
 * <p>生产装配链路：共享 Redis 后端（{@link RedisDistributedRuntimeConfig}）→
 * 租户感知装饰器（{@link TenantAwareDistributedStore}）→ {@code AgentConfigurer} 接线。
 * 隔离机制为在 namespace 列表头部插入租户段，因此同一 agent 在不同租户下应写入
 * 不同的 Redis 键：租户 A 写入的长期记忆对租户 B 不可读、不可搜、CAS 不可覆盖。</p>
 *
 * <p>默认不运行：外部中间件不应成为测试套件的前置依赖。显式开启方式（连接参数取自
 * {@code REDIS_HOST / REDIS_PORT / REDIS_PASSWORD} 系统属性或同名环境变量）：</p>
 * <pre>
 *   mvn -o -pl yunxi-integration-test test -Dyunxi.redis.it=true \
 *       -Dtest=LongTermMemoryTenantIsolationTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfSystemProperty(named = "yunxi.redis.it", matches = "true")
class LongTermMemoryTenantIsolationTest {

    private static final String KEY_PREFIX = "yunxi:e2e:tenant-memory:";

    /** 与平台 Redis 配置的默认口令一致，保证默认参数即可连上本地基础设施。 */
    private static final String DEFAULT_REDIS_PASSWORD = "redispass";

    private static final List<String> MEMORY_NAMESPACE = List.of("agents", "agent-it", "memory");
    private static final String MEMORY_KEY = "MEMORY.md";

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private TenantAwareDistributedStore tenantStore;

    @BeforeEach
    void setUp() {
        String host = property("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(property("REDIS_PORT", "6379"));
        String password = property("REDIS_PASSWORD", DEFAULT_REDIS_PASSWORD);

        factory = factory(host, port, password);
        template = template(factory);
        ping(factory);

        RedisDistributedRuntimeConfig backend = new RedisDistributedRuntimeConfig(template, KEY_PREFIX);
        tenantStore = new TenantAwareDistributedStore(
                backend.distributedStore(), new TenantConfig(true, "default"), null);

        cleanKeys();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        cleanKeys();
        factory.destroy();
    }

    @Test
    @DisplayName("跨租户长期记忆隔离：租户 A 写入后租户 B 不可读、不可搜、CAS 不可覆盖，键物理携带租户段")
    void crossTenantMemoryInvisible() {
        TenantContext.set("tenant-a");
        tenantStore.baseStore().put(MEMORY_NAMESPACE, MEMORY_KEY, Map.of("content", "tenant-a-memory"));

        StoreItem own = tenantStore.baseStore().get(MEMORY_NAMESPACE, MEMORY_KEY);
        assertNotNull(own, "tenant A must read its own memory back");
        assertEquals("tenant-a-memory", own.value().get("content"));

        // 租户 B 视角：读不到、搜不到
        TenantContext.set("tenant-b");
        assertNull(tenantStore.baseStore().get(MEMORY_NAMESPACE, MEMORY_KEY),
                "tenant B must not read tenant A's memory");
        assertTrue(tenantStore.baseStore().search(MEMORY_NAMESPACE, 10, 0).isEmpty(),
                "tenant B must not list tenant A's memory");

        // 租户 B 的 CAS 写（用租户 A 观察到的版本）必须失败，不得覆盖他人键
        assertFalse(
                tenantStore.baseStore().putIfVersion(
                        MEMORY_NAMESPACE, MEMORY_KEY, Map.of("content", "tenant-b-hack"), own.version()),
                "tenant B must not CAS-overwrite tenant A's memory");

        // 键空间隔离的物理证据：真实 Redis 键携带租户段
        Set<String> rawKeys = template.keys(KEY_PREFIX + "*");
        assertNotNull(rawKeys);
        assertTrue(rawKeys.stream().anyMatch(k -> k.contains("tenant-a") && k.contains(MEMORY_KEY)),
                "raw Redis keys must carry the tenant segment, got: " + rawKeys);

        // 未绑定租户上下文 → default 回落，与 tenant-a / tenant-b 均隔离
        TenantContext.clear();
        assertNull(tenantStore.baseStore().get(MEMORY_NAMESPACE, MEMORY_KEY),
                "unbound context must fall back to the default tenant namespace, not see tenant data");
    }

    @Test
    @DisplayName("租户 B 写入后，租户 A 的既有键未被污染，双方各自读写自己的记忆")
    void tenantsWriteIndependentNamespaces() {
        TenantContext.set("tenant-a");
        tenantStore.baseStore().put(MEMORY_NAMESPACE, MEMORY_KEY, Map.of("content", "from-a"));
        TenantContext.set("tenant-b");
        tenantStore.baseStore().put(MEMORY_NAMESPACE, MEMORY_KEY, Map.of("content", "from-b"));

        TenantContext.set("tenant-a");
        assertEquals("from-a", tenantStore.baseStore().get(MEMORY_NAMESPACE, MEMORY_KEY).value().get("content"),
                "tenant A's memory must not be polluted by tenant B's write");
        TenantContext.set("tenant-b");
        assertEquals("from-b", tenantStore.baseStore().get(MEMORY_NAMESPACE, MEMORY_KEY).value().get("content"),
                "tenant B's memory must not be polluted by tenant A's write");
    }

    // ---------- helpers ----------

    private static LettuceConnectionFactory factory(String host, int port, String password) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        if (!password.isBlank()) {
            factory.setPassword(password);
        }
        factory.afterPropertiesSet();
        return factory;
    }

    private static StringRedisTemplate template(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private static void ping(LettuceConnectionFactory factory) {
        RedisConnection connection = factory.getConnection();
        try {
            assertEquals("PONG", connection.ping(), "real Redis must be reachable and authenticated");
        } finally {
            connection.close();
        }
    }

    private void cleanKeys() {
        Set<String> keys = template.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    private static String property(String name, String fallback) {
        // 系统属性显式给出即以其为准（含显式空值：用于连无密码 Redis）；未给出才看环境变量。
        String value = System.getProperty(name);
        if (value != null) {
            return value;
        }
        value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
