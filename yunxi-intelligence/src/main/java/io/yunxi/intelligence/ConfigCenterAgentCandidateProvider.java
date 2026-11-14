package io.yunxi.intelligence;

import io.yunxi.config.ConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.TenantAgentAllocationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 智能平面。基于 {@code ConfigCenter}（Agent 定义唯一真相源）与租户分配服务的候选提供者。
 * 每次调用读当前快照：新增 / 变更的 Agent 定义在下一请求即生效，无需重启、无本地副本。
 *
 * <p>多租户语义：候选 = 平台目录 ∩ 该租户允许集合；租户显式分配为空集合时返回空候选
 * （该租户无可用 Agent，引擎按默认兜底）。未启用多租户（tenantId 为空）时不过滤。</p>
 */
public class ConfigCenterAgentCandidateProvider implements AgentCandidateProvider {

    private final ConfigCenter configCenter;
    private final TenantAgentAllocationService allocationService;

    public ConfigCenterAgentCandidateProvider(ConfigCenter configCenter, TenantAgentAllocationService allocationService) {
        this.configCenter = configCenter;
        this.allocationService = allocationService;
    }

    @Override
    public List<AgentCandidate> candidates(String tenantId, int limit) {
        int effectiveLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        Set<String> allowed = null;
        if (tenantId != null && !tenantId.isBlank()) {
            allowed = allocationService.resolveAllowed(tenantId);
            if (allowed.isEmpty()) {
                return List.of();
            }
        }
        List<AgentCandidate> out = new ArrayList<>();
        for (AgentSpec spec : configCenter.listAgentSpecs()) {
            if (allowed != null && !allowed.contains(spec.identity().agentId())) {
                continue;
            }
            out.add(new AgentCandidate(
                    spec.identity().agentId(),
                    spec.identity().name(),
                    AgentCardWriter.derive(spec)));
            if (out.size() >= effectiveLimit) {
                break;
            }
        }
        return List.copyOf(out);
    }
}
