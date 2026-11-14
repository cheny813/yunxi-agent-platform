package io.yunxi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantAgentAllocationTest {

    @Test
    void resolve_returnsExplicitEntry_whenPresent() {
        var alloc = new TenantAgentAllocation(Map.of("t1", Set.of("a1", "a2")), Set.of());
        assertEquals(Optional.of(Set.of("a1", "a2")), alloc.resolve("t1"));
    }

    @Test
    void resolve_returnsEmptySet_whenExplicitEntryIsEmpty() {
        // 显式空集合 = 允许无 Agent（非 fail-open）；不能回落到 defaultAgents
        var alloc = new TenantAgentAllocation(Map.of("t1", Set.of()), Set.of("a9"));
        assertEquals(Optional.of(Set.of()), alloc.resolve("t1"));
    }

    @Test
    void resolve_fallsBackToDefault_whenNoExplicitEntry() {
        var alloc = new TenantAgentAllocation(Map.of(), Set.of("a9"));
        assertEquals(Optional.of(Set.of("a9")), alloc.resolve("t1"));
    }

    @Test
    void resolve_emptyOptional_whenNoEntryAndNoDefault() {
        var alloc = new TenantAgentAllocation(Map.of(), Set.of());
        assertEquals(Optional.empty(), alloc.resolve("t1"));
    }

    @Test
    void resolve_nullTenant_treatedAsNoAssignment() {
        var alloc = new TenantAgentAllocation(Map.of("t1", Set.of("a1")), Set.of("a9"));
        assertEquals(Optional.of(Set.of("a9")), alloc.resolve(null));
        var noDefault = new TenantAgentAllocation(Map.of(), Set.of());
        assertEquals(Optional.empty(), noDefault.resolve(null));
    }

    @Test
    void nullArgs_normalizedToEmpty() {
        var alloc = new TenantAgentAllocation(null, null);
        assertNotNull(alloc.entries());
        assertNotNull(alloc.defaultAgents());
        assertTrue(alloc.resolve("x").isEmpty());
    }
}
