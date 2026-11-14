package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 验证 {@link A2aServerRegistry} 的 eager 注册、惰性工厂与 memoized get 语义。 */
class A2aServerRegistryTest {

    @Test
    void factoryBuildsOnceAndMemoizes() {
        A2aServerRegistry registry = new A2aServerRegistry();
        AtomicInteger calls = new AtomicInteger();
        AgentScopeA2aServer built = Mockito.mock(AgentScopeA2aServer.class);
        registry.registerFactory("a1", () -> {
            calls.incrementAndGet();
            return built;
        });
        // 未注册 id 返回 null
        assertNull(registry.get("missing"));
        // 首个 get 触发构建
        assertSame(built, registry.get("a1"));
        // 后续 get 命中缓存，不重复构建
        assertSame(built, registry.get("a1"));
        assertEquals(1, calls.get(), "工厂须仅构建一次（memoized）");
    }

    @Test
    void eagerRegistrationTakesPrecedenceOverFactory() {
        A2aServerRegistry registry = new A2aServerRegistry();
        AgentScopeA2aServer eager = Mockito.mock(AgentScopeA2aServer.class);
        AgentScopeA2aServer lazy = Mockito.mock(AgentScopeA2aServer.class);
        registry.registerFactory("a1", () -> lazy);
        registry.register("a1", eager);
        assertSame(eager, registry.get("a1"));
    }
}
