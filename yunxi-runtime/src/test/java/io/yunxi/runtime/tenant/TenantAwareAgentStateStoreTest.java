package io.yunxi.runtime.tenant;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.yunxi.runtime.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantAwareAgentStateStore} 的存储计量挂钩验证：写 / 删路径透传 {@link TenantStorageMeter}，
 * 且仅当租户轴启用、解析到租户时计数；meter 缺失时 fail-open 不抛。
 */
class TenantAwareAgentStateStoreTest {

    private final FakeTenantStorageMeter meter = new FakeTenantStorageMeter();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TenantConfig enabledConfig() {
        return new TenantConfig(true, "t1");
    }

    @Test
    void save_recordsWriteForCurrentTenant() {
        TenantContext.set("t1");
        AgentStateStore store = new TenantAwareAgentStateStore(new NoopAgentStateStore(), enabledConfig(), meter);
        store.save("u1", "s1", "k1", (State) null);
        assertThat(meter.writes).containsExactly("t1");
    }

    @Test
    void saveList_recordsWrite() {
        TenantContext.set("t1");
        AgentStateStore store = new TenantAwareAgentStateStore(new NoopAgentStateStore(), enabledConfig(), meter);
        store.save("u1", "s1", "k1", List.of());
        assertThat(meter.writes).containsExactly("t1");
    }

    @Test
    void saveIfVersion_recordsWriteOnActualWrite() {
        TenantContext.set("t1");
        AgentStateStore store = new TenantAwareAgentStateStore(new NoopAgentStateStore(), enabledConfig(), meter);
        store.saveIfVersion("u1", "s1", "k1", null, 1L);
        assertThat(meter.writes).containsExactly("t1");
    }

    @Test
    void delete_recordsDeleteForCurrentTenant() {
        TenantContext.set("t1");
        AgentStateStore store = new TenantAwareAgentStateStore(new NoopAgentStateStore(), enabledConfig(), meter);
        store.delete("u1", "s1");
        assertThat(meter.deletes).containsExactly("t1");
    }

    @Test
    void deleteByKey_recordsDelete() {
        TenantContext.set("t1");
        AgentStateStore store = new TenantAwareAgentStateStore(new NoopAgentStateStore(), enabledConfig(), meter);
        store.delete("u1", "s1", "k1");
        assertThat(meter.deletes).containsExactly("t1");
    }

    @Test
    void disabled_doesNotRecord() {
        AgentStateStore store = new TenantAwareAgentStateStore(
                new NoopAgentStateStore(), new TenantConfig(false, "t1"), meter);
        store.save("u1", "s1", "k1", (State) null);
        store.delete("u1", "s1");
        assertThat(meter.writes).isEmpty();
        assertThat(meter.deletes).isEmpty();
    }

    @Test
    void missingMeter_doesNotThrow() {
        TenantContext.set("t1");
        AgentStateStore store = new TenantAwareAgentStateStore(new NoopAgentStateStore(), enabledConfig(), null);
        store.save("u1", "s1", "k1", (State) null);
        store.delete("u1", "s1");
        // 无异常、无计量（fail-open）
    }

    /** 最简 no-op 委托，仅满足接口；装饰器计量与键改写不依赖其返回值。 */
    static final class NoopAgentStateStore implements AgentStateStore {
        @Override public void save(String userId, String sessionId, String key, State value) { }
        @Override public <T extends State> VersionedState<T> getVersioned(String userId, String sessionId, String key, Class<T> type) { return null; }
        @Override public long saveIfVersion(String userId, String sessionId, String key, State value, long expectedVersion) { return 1L; }
        @Override public void save(String userId, String sessionId, String key, List<? extends State> values) { }
        @Override public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) { return Optional.empty(); }
        @Override public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) { return List.of(); }
        @Override public boolean exists(String userId, String sessionId) { return false; }
        @Override public void delete(String userId, String sessionId) { }
        @Override public void delete(String userId, String sessionId, String key) { }
        @Override public Set<String> listSessionIds(String userId) { return Set.of(); }
        @Override public void close() { }
    }
}
