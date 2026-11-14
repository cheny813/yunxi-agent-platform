package io.yunxi.app;

import io.yunxi.config.ConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.TenantAgentAllocationService;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制平面。per-tenant 列示端点：列出某租户被授权取用的 Agent 身份（agentId / name / description）。
 *
 * <p>租户经路径 {@code {tenantId}} 显式传入，不依赖 {@code TenantContext} 绑定（与运行时授权闸门解耦，
 * 列示是只读查询，不进入执行链路）。认证由既有 {@code SecurityConfig} 全局拦截（未认证 → 401）；
 * 分配与 Agent 定义真相源均为 {@code ConfigCenter}。越权 / 未定义分配均按 fail-open 返回全量 Agent 目录
 * （与 {@link TenantAgentAllocationService#resolveAllowed} 解析语义一致）。</p>
 */
@RestController
@RequestMapping("/v1/admin/tenants")
public class TenantAgentAllocationAdminController {

    private final TenantAgentAllocationService allocation;
    private final ConfigCenter configCenter;

    public TenantAgentAllocationAdminController(
            TenantAgentAllocationService allocation, ConfigCenter configCenter) {
        this.allocation = allocation;
        this.configCenter = configCenter;
    }

    @GetMapping("/{tenantId}/agents")
    public ResponseEntity<List<AgentSpec.Identity>> listAgents(@PathVariable String tenantId) {
        List<AgentSpec.Identity> items = allocation.resolveAllowed(tenantId).stream()
                .filter(configCenter::containsAgentSpec)
                .map(id -> configCenter.getAgentSpec(id).identity())
                .collect(Collectors.toList());
        return ResponseEntity.ok(items);
    }
}
