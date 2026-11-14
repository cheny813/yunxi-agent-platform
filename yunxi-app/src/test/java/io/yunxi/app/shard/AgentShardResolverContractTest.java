package io.yunxi.app.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * {@link AgentShardResolver} 契约测试。
 * 断言接口方法 {@code owns} / {@code resolveOwnerInstanceKey} 可被 mock 调用并满足基本契约。
 */
class AgentShardResolverContractTest {

    @Test
    void contract_methodsCallable() {
        AgentShardResolver resolver = mock(AgentShardResolver.class);
        when(resolver.owns("a1")).thenReturn(true);
        when(resolver.resolveOwnerInstanceKey("a1")).thenReturn("inst-1");

        assertTrue(resolver.owns("a1"));
        assertEquals("inst-1", resolver.resolveOwnerInstanceKey("a1"));
    }
}
