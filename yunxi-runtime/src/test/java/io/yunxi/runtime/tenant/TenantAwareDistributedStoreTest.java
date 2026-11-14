package io.yunxi.runtime.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link TenantAwareDistributedStore} 与 {@link TenantContext} 的键空间隔离验证。
 *
 * <p>覆盖三条核心路径：启用且解析到租户时改写 userId / 前置 namespace；禁用时完全 pass-through；
 * 未解析到租户时回落 {@code defaultTenant}。并验证租户经 {@link TenantContext#withTenant} 在订阅线程绑定，
 * 使入口网关包裹后的同步执行段（含存储键计算）能读取到租户。
 */
class TenantAwareDistributedStoreTest {

    private final FakeTenantStorageMeter meter = new FakeTenantStorageMeter();

    /** 每个用例后清除 ThreadLocal 基线，避免用例间串扰。 */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 启用且上下文中存在租户：{@code userId} 改写为 {@code t1:alice}，namespace 前置 {@code t1}。 */
    @Test
    void prefixesUserIdAndNamespaceWhenTenantSet() {
        AgentStateStore state = mock(AgentStateStore.class);
        BaseStore base = mock(BaseStore.class);
        DistributedStore delegate = DistributedStore.builder()
                .agentStateStore(state)
                .baseStore(base)
                .build();

        TenantAwareDistributedStore store = new TenantAwareDistributedStore(delegate, new TenantConfig(true, "default"), meter);
        TenantContext.set("t1");

        store.agentStateStore().save("alice", "s1", "k", (State) null);
        verify(state).save(eq("t1:alice"), eq("s1"), eq("k"), nullable(State.class));

        store.baseStore().put(List.of("ws", "mem"), "key", Map.of());
        verify(base).put(eq(List.of("t1", "ws", "mem")), eq("key"), any());
    }

    /** 禁用时即便上下文中存在租户也完全透传，保证存量数据（无前缀）零迁移、行为不变。 */
    @Test
    void passThroughWhenDisabled() {
        AgentStateStore state = mock(AgentStateStore.class);
        BaseStore base = mock(BaseStore.class);
        DistributedStore delegate = DistributedStore.builder()
                .agentStateStore(state)
                .baseStore(base)
                .build();

        TenantAwareDistributedStore store = new TenantAwareDistributedStore(delegate, new TenantConfig(false, "default"), meter);
        TenantContext.set("t1");

        store.agentStateStore().save("alice", "s1", "k", (State) null);
        verify(state).save(eq("alice"), eq("s1"), eq("k"), nullable(State.class));

        store.baseStore().put(List.of("ws"), "key", Map.of());
        verify(base).put(eq(List.of("ws")), eq("key"), any());
    }

    /** 启用但上下文为空：回落到 {@code defaultTenant}，userId 改写为 {@code fallback:alice}。 */
    @Test
    void fallsBackToDefaultTenantWhenContextEmpty() {
        AgentStateStore state = mock(AgentStateStore.class);
        BaseStore base = mock(BaseStore.class);
        DistributedStore delegate = DistributedStore.builder()
                .agentStateStore(state)
                .baseStore(base)
                .build();

        TenantAwareDistributedStore store = new TenantAwareDistributedStore(delegate, new TenantConfig(true, "fallback"), meter);

        store.agentStateStore().save("alice", "s1", "k", (State) null);
        verify(state).save(eq("fallback:alice"), eq("s1"), eq("k"), nullable(State.class));
    }

    /**
     * 租户经 {@link TenantContext#withTenant} 在订阅线程绑定 ThreadLocal：在链内（模拟入口网关包裹后的执行环境）
     * 读取到的租户仍正确改写键，证明 gateway 注入的租户可被存储装饰器在键计算时读取。
     */
    @Test
    void readsTenantBoundByWithTenantOnSubscribeThread() {
        AgentStateStore state = mock(AgentStateStore.class);
        BaseStore base = mock(BaseStore.class);
        DistributedStore delegate = DistributedStore.builder()
                .agentStateStore(state)
                .baseStore(base)
                .build();
        TenantAwareDistributedStore store = new TenantAwareDistributedStore(delegate, new TenantConfig(true, "default"), meter);

        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        // withTenant 在订阅线程绑定 ThreadLocal；链内（网关包裹后的同步执行段）装饰器须读到该租户。
        TenantContext.withTenant(Mono.just("x").flatMap(x -> {
            seen.set(TenantContext.current());
            store.agentStateStore().save("alice", "s1", "k", (State) null);
            return Mono.just("done");
        }), "rt").block();

        assertEquals("rt", seen.get());
        verify(state).save(eq("rt:alice"), eq("s1"), eq("k"), nullable(State.class));
    }

    /** {@link TenantContext#withTenant} 在租户为 null 时原样返回源（装饰器据此 pass-through）。 */
    @Test
    void withTenantPassthroughWhenNull() {
        assertEquals("src", TenantContext.withTenant(Mono.just("src"), null).block());
    }
}
