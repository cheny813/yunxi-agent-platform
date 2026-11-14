package io.yunxi.app;

import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.config.TenantAgentAllocation;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.TenantAgentAllocationService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 固化 per-tenant 列示端点逻辑：租户经路径 {tenantId} 显式传入，返回该租户被授权 Agent 的
 * AgentSpec.Identity（agentId / name / description）；无分配按 fail-open 返回全量目录。
 * 认证（未认证 401）由既有 SecurityConfig 全局拦截，不在本测试范围。
 */
class TenantAgentAllocationAdminControllerTest {

    private TenantAgentAllocationAdminController controller(InMemoryConfigCenter center) {
        return new TenantAgentAllocationAdminController(new TenantAgentAllocationService(center), center);
    }

    private AgentSpec spec(String id) {
        return new AgentSpec(new AgentSpec.Identity(id, id.toUpperCase(), "desc-" + id),
                null, null, null, null, null, null, null);
    }

    @Test
    void listsOnlyAllowedAgentsForTenant() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        center.registerAgentSpec(spec("a1"));
        center.registerAgentSpec(spec("a2"));
        center.registerAgentSpec(spec("a3"));
        center.registerTenantAllocation(new TenantAgentAllocation(Map.of("t1", Set.of("a1", "a2")), Set.of()));

        ResponseEntity<List<AgentSpec.Identity>> resp = controller(center).listAgents("t1");
        assertEquals(200, resp.getStatusCodeValue());
        assertEquals(Set.of("a1", "a2"),
                resp.getBody().stream().map(AgentSpec.Identity::agentId).collect(Collectors.toSet()));
    }

    @Test
    void failOpenToFullCatalogWhenNoAllocation() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        center.registerAgentSpec(spec("a1"));
        center.registerAgentSpec(spec("a2"));
        center.registerAgentSpec(spec("a3"));

        ResponseEntity<List<AgentSpec.Identity>> resp = controller(center).listAgents("t-unknown");
        assertEquals(200, resp.getStatusCodeValue());
        assertEquals(Set.of("a1", "a2", "a3"),
                resp.getBody().stream().map(AgentSpec.Identity::agentId).collect(Collectors.toSet()));
    }
}
