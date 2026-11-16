package io.yunxi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.legacy.ToolkitState;
import io.yunxi.fabric.RedisDistributedRuntimeConfig;
import io.yunxi.runtime.tenant.TenantAwareDistributedStore;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.List;
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
 * 在真实 Redis 上验证会话状态（{@link AgentStateStore}）的端到端跨租户隔离。
 *
 * <p>生产装配链路：共享 Redis 后端 → 租户感知装饰器 → {@code AgentConfigurer} 接线。
 * 隔离机制为改写 userId 命名空间组件（前置 {@code tid:}），同一 {@code (userId, sessionId)}
 * 在不同租户下互相不可见：保存、读取、会话列举与匿名会话均按租户隔离。</p>
 *
 * <p>默认不运行：外部中间件不应成为测试套件的前置依赖。显式开启方式（连接参数取自
 * {@code REDIS_HOST / REDIS_PORT / REDIS_PASSWORD} 系统属性或同名环境变量）：</p>
 * <pre>
 *   mvn -o -pl yunxi-integration-test test -Dyunxi.redis.it=true \
 *       -Dtest=TenantEndToEndIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfSystemProperty(named = "yunxi.redis.it", matches = "true")
class TenantEndToEndIntegrationTest {

    private static final String KEY_PREFIX = "yunxi:e2e:tenant-state:";

    /** 与平台 Redis 配置的默认口令一致，保证默认参数即可连上本地基础设施。 */
    private static final String DEFAULT_REDIS_PASSWORD = "redispass";

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "session-it";
    private static final String STATE_KEY = "agent_state";

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private AgentStateStore states;

    @BeforeEach
    void setUp() {
        String host = property("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(property("REDIS_PORT", "6379"));
        String password = property("REDIS_PASSWORD", DEFAULT_REDIS_PASSWORD);

        factory = factory(host, port, password);
        template = template(factory);
        ping(factory);

        RedisDistributedRuntimeConfig backend = new RedisDistributedRuntimeConfig(template, KEY_PREFIX);
        states = new TenantAwareDistributedStore(
                backend.distributedStore(), new TenantConfig(true, "default"), null).agentStateStore();

        cleanKeys();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        cleanKeys();
        factory.destroy();
    }

    @Test
    @DisplayName("跨租户会话状态隔离：租户 A 保存的会话对租户 B 不可见（保存/读取/存在性/列举全隔离）")
    void tenantScopedStateInvisibleAcrossTenants() {
        TenantContext.set("tenant-a");
        states.save(USER_ID, SESSION_ID, STATE_KEY, new ToolkitState(List.of("group-a")));
        assertTrue(states.exists(USER_ID, SESSION_ID), "tenant A must see its own session");
        assertEquals(List.of("group-a"),
                states.get(USER_ID, SESSION_ID, STATE_KEY, ToolkitState.class).orElseThrow().activeGroups());
        assertEquals(Set.of(SESSION_ID), states.listSessionIds(USER_ID),
                "tenant A must list its own session");

        TenantContext.set("tenant-b");
        assertFalse(states.exists(USER_ID, SESSION_ID),
                "tenant B must not see tenant A's session");
        assertTrue(states.get(USER_ID, SESSION_ID, STATE_KEY, ToolkitState.class).isEmpty(),
                "tenant B must not read tenant A's state");
        assertTrue(states.listSessionIds(USER_ID).isEmpty(),
                "tenant B must not list tenant A's sessions");
    }

    @Test
    @DisplayName("匿名会话同样按租户隔离：userId=null 时各自归入本租户命名空间")
    void anonymousSessionsAreTenantScoped() {
        TenantContext.set("tenant-a");
        states.save(null, SESSION_ID, STATE_KEY, new ToolkitState(List.of("anon-a")));
        assertTrue(states.exists(null, SESSION_ID), "tenant A must see its own anonymous session");

        TenantContext.set("tenant-b");
        assertFalse(states.exists(null, SESSION_ID),
                "tenant B must not see tenant A's anonymous session");

        TenantContext.set("tenant-a");
        assertTrue(states.exists(null, SESSION_ID), "tenant A's anonymous session must remain intact");
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
