package io.yunxi.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.config.TenantAgentAllocation;
import io.yunxi.definition.AgentSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantAgentAllocationServiceTest {

    private static AgentSpec spec(String id) {
        return new AgentSpec(new AgentSpec.Identity(id, id, id), null, null, null, null, null, null, null);
    }

    private static InMemoryConfigCenter centerWith(List<AgentSpec> specs) {
        InMemoryConfigCenter c = new InMemoryConfigCenter();
        specs.forEach(c::registerAgentSpec);
        return c;
    }

    @Test
    void resolveAllowed_returnsExplicitEntry_andAssertEnforces() {
        InMemoryConfigCenter c = centerWith(List.of(spec("a1"), spec("a2"), spec("a3")));
        c.registerTenantAllocation(new TenantAgentAllocation(Map.of("t1", Set.of("a1")), Set.of()));
        TenantAgentAllocationService svc = new TenantAgentAllocationService(c);

        assertEquals(Set.of("a1"), svc.resolveAllowed("t1"));
        assertDoesNotThrow(() -> svc.assertAllowed("t1", "a1"));
        assertThrows(NoSuchAgentException.class, () -> svc.assertAllowed("t1", "a2"));
    }

    @Test
    void resolveAllowed_explicitEmptySet_allowsNone() {
        InMemoryConfigCenter c = centerWith(List.of(spec("a1")));
        c.registerTenantAllocation(new TenantAgentAllocation(Map.of("t1", Set.of()), Set.of()));
        TenantAgentAllocationService svc = new TenantAgentAllocationService(c);

        assertTrue(svc.resolveAllowed("t1").isEmpty());
        assertThrows(NoSuchAgentException.class, () -> svc.assertAllowed("t1", "a1"));
    }

    @Test
    void resolveAllowed_failOpenAll_whenNoAllocation() {
        InMemoryConfigCenter c = centerWith(List.of(spec("a1"), spec("a2")));
        c.registerTenantAllocation(new TenantAgentAllocation(Map.of(), Set.of()));
        TenantAgentAllocationService svc = new TenantAgentAllocationService(c);

        assertEquals(Set.of("a1", "a2"), svc.resolveAllowed("t1"));
        assertDoesNotThrow(() -> svc.assertAllowed("t1", "a2"));
    }

    @Test
    void resolveAllowed_fallsBackToDefault() {
        InMemoryConfigCenter c = centerWith(List.of(spec("a1"), spec("a9")));
        c.registerTenantAllocation(new TenantAgentAllocation(Map.of(), Set.of("a9")));
        TenantAgentAllocationService svc = new TenantAgentAllocationService(c);

        assertEquals(Set.of("a9"), svc.resolveAllowed("t1"));
        assertDoesNotThrow(() -> svc.assertAllowed("t1", "a9"));
        assertThrows(NoSuchAgentException.class, () -> svc.assertAllowed("t1", "a1"));
    }

    @Test
    void resolveAllowed_nullTenant_fallsToDefaultOrFailOpen() {
        // null 租户：无显式条目 → 回落全局默认 a9
        InMemoryConfigCenter c = centerWith(List.of(spec("a1"), spec("a9")));
        c.registerTenantAllocation(new TenantAgentAllocation(Map.of("t1", Set.of("a1")), Set.of("a9")));
        TenantAgentAllocationService svc = new TenantAgentAllocationService(c);
        assertEquals(Set.of("a9"), svc.resolveAllowed(null));

        // null 租户 + 无默认 → fail-open 全量
        InMemoryConfigCenter c2 = centerWith(List.of(spec("a1")));
        c2.registerTenantAllocation(new TenantAgentAllocation(Map.of(), Set.of()));
        TenantAgentAllocationService svc2 = new TenantAgentAllocationService(c2);
        assertEquals(Set.of("a1"), svc2.resolveAllowed(null));
    }
}
