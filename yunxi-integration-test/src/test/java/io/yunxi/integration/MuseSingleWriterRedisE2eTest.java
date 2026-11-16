package io.yunxi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.yunxi.fabric.RedisDistributedRuntimeConfig;
import io.yunxi.fabric.RedisSessionTurnGate;
import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.refine.SkillRefiner;
import io.yunxi.runtime.ModelFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 在真实 Redis 上验证 MUSE 技能演进的跨副本单写者闸门。
 *
 * <p>模拟同一集群内的两个副本：各自持有独立的 Lettuce 连接工厂与
 * {@link RedisDistributedRuntimeConfig}（即独立的 {@link RedisSessionTurnGate}），
 * 但共享同一 Redis 键前缀——这正是多实例部署下的真实形态。并发演进同一技能时，
 * 闸门必须保证互斥：一方持锁写入，另一方快速失败（TurnBusy），且不产生半写。</p>
 *
 * <p>默认不运行：外部中间件不应成为测试套件的前置依赖。显式开启方式（连接参数取自
 * {@code REDIS_HOST / REDIS_PORT / REDIS_PASSWORD} 系统属性或同名环境变量）：</p>
 * <pre>
 *   mvn -o -pl yunxi-integration-test test -Dyunxi.redis.it=true \
 *       -Dtest=MuseSingleWriterRedisE2eTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>连接参数缺省值与平台 Redis 配置保持一致（{@code REDIS_HOST=127.0.0.1}、
 * {@code REDIS_PORT=6379}、{@code REDIS_PASSWORD=redispass}），因此默认参数即可连上
 * 本地基础设施。连无密码 Redis 时显式传空的系统属性，即 {@code -DREDIS_PASSWORD=}。</p>
 */
@EnabledIfSystemProperty(named = "yunxi.redis.it", matches = "true")
class MuseSingleWriterRedisE2eTest {

    /** 两个副本共享的集群键前缀（闸门锁键即挂在此前缀下）。 */
    private static final String KEY_PREFIX = "yunxi:e2e:muse-single-writer:";

    /**
     * SkillRefiner 的技能演进锁键前缀（与 SkillRefiner.SKILL_LOCK_PREFIX 对齐）。
     * 测试副本 A 直接按此键持锁；若前缀错配，副本 B 的 persist 会成功而使断言失败，
     * 因此本测试同时锁定了该键空间契约。
     */
    private static final String SKILL_LOCK_PREFIX = "muse:skill:default:";

    private static final String SKILL_NAME = "e2e-single-writer-skill";

    /** 与平台 Redis 配置的默认口令一致，保证默认参数即可连上本地基础设施。 */
    private static final String DEFAULT_REDIS_PASSWORD = "redispass";

    private LettuceConnectionFactory factoryA;
    private LettuceConnectionFactory factoryB;
    private StringRedisTemplate templateA;
    private StringRedisTemplate templateB;
    private RedisDistributedRuntimeConfig configA;
    private RedisDistributedRuntimeConfig configB;
    private SkillRefiner refinerA;
    private SkillRefiner refinerB;
    private Path skillDir;

    @BeforeEach
    void setUp() {
        String host = property("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(property("REDIS_PORT", "6379"));
        String password = property("REDIS_PASSWORD", DEFAULT_REDIS_PASSWORD);

        factoryA = factory(host, port, password);
        factoryB = factory(host, port, password);
        templateA = template(factoryA);
        templateB = template(factoryB);

        ping(factoryA);
        ping(factoryB);

        configA = new RedisDistributedRuntimeConfig(templateA, KEY_PREFIX);
        configB = new RedisDistributedRuntimeConfig(templateB, KEY_PREFIX);

        ModelFactory unusedModelFactory = modelId -> null;
        refinerA = new SkillRefiner(unusedModelFactory, emptyRepoProvider(), new EvolutionConfig(), configA);
        refinerB = new SkillRefiner(unusedModelFactory, emptyRepoProvider(), new EvolutionConfig(), configB);

        try {
            skillDir = Files.createTempDirectory("muse-e2e-skill");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create temp skill dir", e);
        }
        cleanKeys();
    }

    @AfterEach
    void tearDown() {
        cleanKeys();
        ((RedisSessionTurnGate) configA.sessionTurnGate()).shutdown();
        ((RedisSessionTurnGate) configB.sessionTurnGate()).shutdown();
        factoryA.destroy();
        factoryB.destroy();
        deleteRecursively(skillDir);
    }

    @Test
    @DisplayName("跨副本互斥：副本 A 持锁期间副本 B 快速失败且不半写，锁释放后 B 写入成功")
    void crossReplicaPersistMutualExclusion() throws Exception {
        String lockKey = SKILL_LOCK_PREFIX + SKILL_NAME;

        try (var lease = configA.sessionTurnGate().acquire(lockKey)) {
            assertFalse(refinerB.persist(SKILL_NAME, "content-from-B", skillDir.toString()),
                    "replica B must fast-fail while replica A holds the evolution lock");
            assertFalse(Files.exists(skillDir.resolve("SKILL.md")),
                    "rejected replica must not leave a partial write behind");
        }

        assertTrue(refinerB.persist(SKILL_NAME, "content-from-B", skillDir.toString()),
                "replica B must succeed once the lock is released");
        assertEquals("content-from-B", Files.readString(skillDir.resolve("SKILL.md")));
    }

    @Test
    @DisplayName("persist 成功后立即释放锁：副本 B 无需等待即可写入同一技能")
    void persistReleasesLockImmediately() {
        assertTrue(refinerA.persist(SKILL_NAME, "content-from-A", skillDir.toString()));
        assertTrue(refinerB.persist(SKILL_NAME, "content-from-B", skillDir.toString()),
                "lock must be released by A's persist; B would busy-fail after 3s otherwise");
    }

    @Test
    @DisplayName("双副本并发演进同一技能：全部收敛、磁盘正文始终为任一候选的完整内容（无半写损坏）")
    void concurrentEvolutionsConvergeWithoutCorruption() throws Exception {
        List<String> candidates = List.of(
                "candidate-0", "candidate-1", "candidate-2",
                "candidate-3", "candidate-4", "candidate-5");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                String content = candidates.get(i);
                SkillRefiner refiner = (i % 2 == 0) ? refinerA : refinerB;
                tasks.add(() -> refiner.persist(SKILL_NAME, content, skillDir.toString()));
            }
            List<Future<Boolean>> futures = pool.invokeAll(tasks);
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> f : futures) {
                outcomes.add(f.get(10, TimeUnit.SECONDS));
            }
            assertTrue(outcomes.contains(true),
                    "all busy would mean nothing ever wrote; gate state is suspicious");
        } finally {
            pool.shutdownNow();
        }

        String onDisk = Files.readString(skillDir.resolve("SKILL.md"));
        assertTrue(candidates.contains(onDisk),
                "on-disk content must be one full candidate, got: " + onDisk);
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

    private static ObjectProvider<AgentSkillRepository> emptyRepoProvider() {
        return new ObjectProvider<>() {
            @Override
            public AgentSkillRepository getObject() {
                return null;
            }

            @Override
            public AgentSkillRepository getIfAvailable() {
                return null;
            }

            @Override
            public AgentSkillRepository getIfUnique() {
                return null;
            }
        };
    }

    private void cleanKeys() {
        Set<String> keys = templateA.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            templateA.delete(keys);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
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
