package io.yunxi.runtime.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 技能租户路由仓库单测：读取/写入按 {@link TenantContext} 当前租户委托到对应
 * namespace 的后端仓库；无租户上下文与非法 namespace 字符均 fail-open 落默认 scope。
 */
class TenantRoutingSkillRepositoryTest {

    /** 按 namespace 记账的内存后端仓库（工厂每次调用新建，计数器验证缓存语义）。 */
    private static final class RecordingRepo implements AgentSkillRepository {

        private final String namespace;
        private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();
        volatile boolean closed;

        RecordingRepo(String namespace) {
            this.namespace = namespace;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.get(name);
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.copyOf(skills.keySet());
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return List.copyOf(skills.values());
        }

        @Override
        public boolean save(List<AgentSkill> toSave, boolean force) {
            toSave.forEach(skill -> skills.put(skill.getName(), skill));
            return true;
        }

        @Override
        public boolean delete(String skillName) {
            return skills.remove(skillName) != null;
        }

        @Override
        public boolean skillExists(String skillName) {
            return skills.containsKey(skillName);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return mock(AgentSkillRepositoryInfo.class);
        }

        @Override
        public String getSource() {
            return "recording_" + namespace;
        }

        @Override
        public void setWriteable(boolean writeable) {
            // 测试桩：不追踪该状态
        }

        @Override
        public boolean isWriteable() {
            return true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final ConcurrentHashMap<String, RecordingRepo> backends = new ConcurrentHashMap<>();
    private final AtomicInteger factoryCalls = new AtomicInteger();
    private TenantRoutingSkillRepository routing;

    @BeforeEach
    void setUp() {
        routing = new TenantRoutingSkillRepository(namespace -> {
            factoryCalls.incrementAndGet();
            return backends.computeIfAbsent(namespace, RecordingRepo::new);
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("租户 A 写入的技能对租户 B 不可见（scope 互不污染）")
    void tenantAWriteInvisibleToTenantB() {
        TenantContext.set("tenant-a");
        AgentSkill skillA = new AgentSkill("orders-export", "A 专属", "content", Map.of());
        assertTrue(routing.save(List.of(skillA), true));

        TenantContext.set("tenant-b");
        assertFalse(routing.skillExists("orders-export"), "租户 B 不得看到租户 A 的技能");
        assertTrue(routing.getAllSkills().isEmpty());
        assertNull(routing.getSkill("orders-export"));

        TenantContext.set("tenant-a");
        assertTrue(routing.skillExists("orders-export"));
        assertSame(skillA, routing.getSkill("orders-export"));
    }

    @Test
    @DisplayName("无租户上下文落默认 scope（fail-open，与既有默认 namespace 数据兼容）")
    void missingTenantContextFallsBackToDefaultNamespace() {
        assertTrue(routing.save(List.of(new AgentSkill("legacy", "d", "c", Map.of())), true));
        assertTrue(backends.containsKey(TenantRoutingSkillRepository.DEFAULT_NAMESPACE),
                "无租户上下文须路由到默认 namespace");
        assertEquals("default", TenantRoutingSkillRepository.DEFAULT_NAMESPACE,
                "默认 namespace 须与框架 JdbcAgentSkillRepository 默认值一致，保证既有数据可见");
    }

    @Test
    @DisplayName("每租户后端实例缓存：同租户多次调用工厂只建一次")
    void backendInstancesCachedPerTenant() {
        TenantContext.set("tenant-a");
        routing.skillExists("x");
        routing.skillExists("x");
        TenantContext.set("tenant-b");
        routing.skillExists("x");

        assertEquals(2, factoryCalls.get(), "租户 a/b 各触发一次工厂，缓存复用不重复建");
    }

    @Test
    @DisplayName("非法 namespace 字符（框架 [A-Za-z0-9._-] 之外）fail-open 落默认 scope")
    void illegalNamespaceCharactersFallBackToDefault() {
        TenantContext.set("tenant/with:illegal");
        assertTrue(routing.save(List.of(new AgentSkill("s", "d", "c", Map.of())), true));
        assertTrue(backends.containsKey(TenantRoutingSkillRepository.DEFAULT_NAMESPACE),
                "非法 namespace 字符不得透传给后端构造器（框架会抛 IllegalArgumentException），须落默认 scope");
        assertEquals(1, backends.size());
    }

    @Test
    @DisplayName("delete 路由到当前租户 scope，且不跨 scope 删除同名技能")
    void deleteRoutesToCurrentTenantOnly() {
        TenantContext.set("tenant-a");
        routing.save(List.of(new AgentSkill("shared-name", "d", "c", Map.of())), true);
        TenantContext.set("tenant-b");
        routing.save(List.of(new AgentSkill("shared-name", "d", "c", Map.of())), true);

        TenantContext.set("tenant-a");
        assertTrue(routing.delete("shared-name"));

        TenantContext.set("tenant-b");
        assertTrue(routing.skillExists("shared-name"), "删除不得波及其他 scope 的同名技能");
    }

    @Test
    @DisplayName("close 关闭全部缓存的后端实例")
    void closeShutsDownAllCachedBackends() {
        TenantContext.set("tenant-a");
        routing.skillExists("x");
        TenantContext.set("tenant-b");
        routing.skillExists("x");

        RecordingRepo repoA = backends.get("tenant-a");
        RecordingRepo repoB = backends.get("tenant-b");
        routing.close();

        assertTrue(repoA.closed, "租户 a 的后端实例须随 close 关闭");
        assertTrue(repoB.closed, "租户 b 的后端实例须随 close 关闭");
    }
}
