package io.yunxi.runtime.tenant;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.yunxi.definition.TenantStorageMeter;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 装饰 {@link AgentStateStore}：在 userId 命名空间组件前置 {@code tenant:{tid}:}，实现键空间隔离。
 *
 * <p>框架 {@code AgentStateStore} 以 {@code (userId, sessionId, key)} 三元组为键，并明示
 * 「调用方不可手动拼接键，由实现自行组合命名空间」——故租户隔离 = 改写 userId 命名空间组件，
 * 而非触碰框架内部键结构。匿名会话（userId=null）归入 {@code tenant:{tid}:anon}，保持与框架语义一致。
 *
 * <p>{@code enabled=false} 时 pass-through（不改写 userId），保证存量数据零迁移。
 *
 * <p>写 / 删路径透传 {@link TenantStorageMeter} 做近似存储计量（租户轴唯一键空间边界即计量挂钩点，单一真相源）。
 * meter 为 null（未接线）时静默跳过，fail-open。
 */
final class TenantAwareAgentStateStore implements AgentStateStore {

    private final AgentStateStore delegate;
    private final TenantConfig config;
    private final TenantStorageMeter meter;

    TenantAwareAgentStateStore(AgentStateStore delegate, TenantConfig config, TenantStorageMeter meter) {
        this.delegate = delegate;
        this.config = config;
        this.meter = meter;
    }

    private String scope(String userId) {
        String tid = currentTenant();
        if (tid == null) {
            return userId;
        }
        return tid + ":" + (userId == null ? "anon" : userId);
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
   public void save(String userId, String sessionId, String key, State value) {
        delegate.save(scope(userId), sessionId, key, value);
        recordWrite();
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(String userId, String sessionId, String key, Class<T> type) {
        return delegate.getVersioned(scope(userId), sessionId, key, type);
    }

    @Override
    public long saveIfVersion(String userId, String sessionId, String key, State value, long expectedVersion) {
        long result = delegate.saveIfVersion(scope(userId), sessionId, key, value, expectedVersion);
        if (result != AgentStateStore.UNVERSIONED) {
            recordWrite();
        }
        return result;
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        delegate.save(scope(userId), sessionId, key, values);
        recordWrite();
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(scope(userId), sessionId, key, type);
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        return delegate.getList(scope(userId), sessionId, key, itemType);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(scope(userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(scope(userId), sessionId);
        recordDelete();
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(scope(userId), sessionId, key);
        recordDelete();
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(scope(userId));
    }

    @Override
    public void close() {
        delegate.close();
    }
}
