package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.TenantAgentAllocationService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 候选提供者：ConfigCenter 目录 ∩ 租户允许集合，limit 截断，卡片派生。 */
class ConfigCenterAgentCandidateProviderTest {

    private static AgentSpec spec(String agentId, String name, String description) {
        return new AgentSpec(new AgentSpec.Identity(agentId, name, description), null, null, null, null, null, null, null);
    }

    private ConfigCenterAgentCandidateProvider provider(InMemoryConfigCenter center) {
        return new ConfigCenterAgentCandidateProvider(center, new TenantAgentAllocationService(center));
    }

    @Test
    void listsAllAgentsWhenNoTenant() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        center.registerAgentSpec(spec("a1", "A1", "你是一个助手。"));
        center.registerAgentSpec(spec("a2", "A2", "另一个。"));
        List<AgentCandidate> got = provider(center).candidates(null, 8);
        assertEquals(2, got.size());
        assertEquals("a1", got.get(0).agentId());
        assertEquals("A1：助手。", got.get(0).card());
    }

    @Test
    void filtersByTenantAllocation() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        center.registerAgentSpec(spec("a1", "A1", "助手。"));
        center.registerAgentSpec(spec("a2", "A2", "数据库。"));
        center.registerTenantAllocation(new io.yunxi.config.TenantAgentAllocation(
                java.util.Map.of("t1", java.util.Set.of("a1")), java.util.Set.of()));
        List<AgentCandidate> got = provider(center).candidates("t1", 8);
        assertEquals(1, got.size());
        assertEquals("a1", got.get(0).agentId());
    }

    @Test
    void emptyAllocationMeansNoCandidates() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        center.registerAgentSpec(spec("a1", "A1", "助手。"));
        center.registerTenantAllocation(new io.yunxi.config.TenantAgentAllocation(
                java.util.Map.of("t1", java.util.Set.of()), java.util.Set.of()));
        assertTrue(provider(center).candidates("t1", 8).isEmpty());
    }

    @Test
    void truncatesToLimit() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        center.registerAgentSpec(spec("a1", "A1", "一。"));
        center.registerAgentSpec(spec("a2", "A2", "二。"));
        center.registerAgentSpec(spec("a3", "A3", "三。"));
        assertEquals(2, provider(center).candidates(null, 2).size());
    }
}
