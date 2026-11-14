package io.yunxi.config;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 租户 → Agent 分配（per-tenant 允许列表）。产品平面配置实体，是分配的单一真相源。
 *
 * <p>与 {@link AgentSpec} 一样是纯数据，不携带运行时 / 框架语义。持久化在 {@link ConfigCenter}
 * （Nacos 独立 dataId 快照 + 监听，保证多实例一致），运行时由
 * {@code io.yunxi.runtime.TenantAgentAllocationService} 解析并注入四协议授权闸门。</p>
 *
 * <p>解析语义（见 {@link #resolve}）：
 * <ul>
 *   <li>租户显式条目存在 → 返回该集合（可能为空集合 = 允许无 Agent）；</li>
 *   <li>否则回落全局默认（若非空）；</li>
 *   <li>否则返回 {@link Optional#empty()} 表示「未定义分配」→ 授权 fail-open 全部允许（向后兼容）。</li>
 * </ul>
 */
public record TenantAgentAllocation(Map<String, Set<String>> entries, Set<String> defaultAgents) {

    public TenantAgentAllocation {
        entries = entries == null ? Collections.emptyMap() : entries;
        defaultAgents = defaultAgents == null ? Collections.emptySet() : defaultAgents;
    }

    /**
     * 解析某租户被允许取用的 Agent 集合。
     *
     * @param tenantId 租户标识（可为 null：未解析租户按「未定义分配」处理，回落全局默认或 fail-open）
     * @return 显式集合（{@link Optional#of}，可能为空集合），或 {@link Optional#empty()}（未定义 → fail-open 全部）
     */
    public Optional<Set<String>> resolve(String tenantId) {
        if (tenantId != null) {
            Set<String> explicit = entries.get(tenantId);
            if (explicit != null) {
                return Optional.of(explicit);
            }
        }
        if (!defaultAgents.isEmpty()) {
            return Optional.of(defaultAgents);
        }
        return Optional.empty();
    }
}
