package io.yunxi.runtime.tenant;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.yunxi.definition.TenantStorageMeter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 装饰 {@link BaseStore}：在 namespace 列表前置 {@code tenant:{tid}}，实现键空间隔离。
 *
 * <p>框架 {@code BaseStore} 以 {@code (List<String> namespace, key)} 为键，namespace 即分层路径，
 * 故租户隔离 = 在 namespace 列表头部插入 tenant 段。{@code enabled=false} 时 pass-through。
 *
 * <p>写 / 删路径透传 {@link TenantStorageMeter} 做近似存储计量（租户轴唯一键空间边界即计量挂钩点，单一真相源）。
 * meter 为 null（未接线）时静默跳过，fail-open。
 */
final class TenantAwareBaseStore implements BaseStore {

    private final BaseStore delegate;
    private final TenantConfig config;
    private final TenantStorageMeter meter;

    TenantAwareBaseStore(BaseStore delegate, TenantConfig config, TenantStorageMeter meter) {
        this.delegate = delegate;
        this.config = config;
        this.meter = meter;
    }

    private List<String> scope(List<String> namespace) {
        String tid = currentTenant();
        if (tid == null) {
            return namespace;
        }
        List<String> scoped = new ArrayList<>(namespace.size() + 1);
        scoped.add(tid);
        scoped.addAll(namespace);
        return scoped;
    }

    private String currentTenant() {
        if (!config.enabled()) {
            return null;
        }
        String t = TenantContext.current();
        return t != null ? t : config.defaultTenant();
    }

    private void recordWrite() {
        if (meter != null) {
            String t = currentTenant();
            if (t != null) {
                meter.recordWrite(t);
            }
        }
    }

    private void recordDelete() {
        if (meter != null) {
            String t = currentTenant();
            if (t != null) {
                meter.recordDelete(t);
            }
        }
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        return delegate.get(scope(namespace), key);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        delegate.put(scope(namespace), key, value);
        recordWrite();
    }

    @Override
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        boolean written = delegate.putIfVersion(scope(namespace), key, value, expectedVersion);
        if (written) {
            recordWrite();
        }
        return written;
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        return delegate.search(scope(namespace), limit, offset);
    }

    @Override
    public void delete(List<String> namespace, String key) {
        delegate.delete(scope(namespace), key);
        recordDelete();
    }
}
