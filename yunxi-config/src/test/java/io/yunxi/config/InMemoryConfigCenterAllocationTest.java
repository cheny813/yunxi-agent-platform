package io.yunxi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryConfigCenterAllocationTest {

    @Test
    void registerThenGet_roundTrips() {
        var cc = new InMemoryConfigCenter();
        var alloc = new TenantAgentAllocation(Map.of("t1", Set.of("a1")), Set.of("a9"));
        cc.registerTenantAllocation(alloc);
        assertEquals(alloc, cc.getTenantAllocation());
        assertEquals(Set.of("a1"), cc.getTenantAllocation().resolve("t1").orElseThrow());
    }

    @Test
    void defaultIsEmptyAllocation() {
        var cc = new InMemoryConfigCenter();
        assertTrue(cc.getTenantAllocation().entries().isEmpty());
        assertTrue(cc.getTenantAllocation().defaultAgents().isEmpty());
    }
}
