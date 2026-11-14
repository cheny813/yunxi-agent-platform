package io.yunxi.runtime.tenant;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.yunxi.runtime.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantAwareBaseStore} 的存储计量挂钩验证：写 / 删路径透传 {@link TenantStorageMeter}，
 * 且仅当租户轴启用、解析到租户时计数；meter 缺失时 fail-open 不抛。
 */
class TenantAwareBaseStoreTest {

    private final FakeTenantStorageMeter meter = new FakeTenantStorageMeter();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TenantConfig enabledConfig() {
        return new TenantConfig(true, "t1");
    }

    @Test
    void put_recordsWriteForCurrentTenant() {
        TenantContext.set("t1");
        BaseStore store = new TenantAwareBaseStore(new NoopBaseStore(), enabledConfig(), meter);
        store.put(List.of("ws"), "k", Map.of());
        assertThat(meter.writes).containsExactly("t1");
    }

    @Test
    void putIfVersion_recordsWriteOnActualWrite() {
        TenantContext.set("t1");
        BaseStore store = new TenantAwareBaseStore(new NoopBaseStore(), enabledConfig(), meter);
        store.putIfVersion(List.of("ws"), "k", Map.of(), 1L);
        assertThat(meter.writes).containsExactly("t1");
    }

    @Test
    void delete_recordsDeleteForCurrentTenant() {
        TenantContext.set("t1");
        BaseStore store = new TenantAwareBaseStore(new NoopBaseStore(), enabledConfig(), meter);
        store.delete(List.of("ws"), "k");
        assertThat(meter.deletes).containsExactly("t1");
    }

    @Test
    void disabled_doesNotRecord() {
        BaseStore store = new TenantAwareBaseStore(new NoopBaseStore(), new TenantConfig(false, "t1"), meter);
        store.put(List.of("ws"), "k", Map.of());
        store.delete(List.of("ws"), "k");
        assertThat(meter.writes).isEmpty();
        assertThat(meter.deletes).isEmpty();
    }

    @Test
    void missingMeter_doesNotThrow() {
        TenantContext.set("t1");
        BaseStore store = new TenantAwareBaseStore(new NoopBaseStore(), enabledConfig(), null);
        store.put(List.of("ws"), "k", Map.of());
        store.delete(List.of("ws"), "k");
        // 无异常、无计量（fail-open）
    }

    /** 最简 no-op 委托，仅满足接口。 */
    static final class NoopBaseStore implements BaseStore {
        @Override public StoreItem get(List<String> namespace, String key) { return null; }
        @Override public void put(List<String> namespace, String key, Map<String, Object> value) { }
        @Override public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long expectedVersion) { return true; }
        @Override public List<StoreItem> search(List<String> namespace, int limit, int offset) { return List.of(); }
        @Override public void delete(List<String> namespace, String key) { }
    }
}
