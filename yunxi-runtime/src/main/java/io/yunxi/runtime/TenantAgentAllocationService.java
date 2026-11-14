package io.yunxi.runtime;

import io.yunxi.config.ConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.config.TenantAgentAllocation;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 租户 → Agent 分配解析与授权。产品平面运行时服务，是分配真相源（{@link ConfigCenter}）的消费者之一。
 *
 * <p>解析语义（见 {@link TenantAgentAllocation#resolve}）：租户显式条目 → 该集合（可能为空 = 允许无 Agent）；
 * 否则全局默认；否则 fail-open 返回当前全量 Agent 目录（向后兼容）。</p>
 *
 * <p>授权在四协议各自真实闸门（{@code DefaultExecutionFacade} / {@code YunxiChatCompletionsController} /
 * {@code A2aController} / {@code YunxiGuardedAguiAgentAdapter}）分别注入本服务，配合各入口自身绑定的
 * {@code TenantContext.current()}，不改动任何框架注册表 / 协议层。</p>
 */
public class TenantAgentAllocationService {

    private final ConfigCenter configCenter;

    public TenantAgentAllocationService(ConfigCenter configCenter) {
        this.configCenter = configCenter;
    }

    /**
     * 解析某租户被允许取用的 Agent 集合。
     * <ul>
     *   <li>显式条目（含空集合）或全局默认 → 返回该集合；</li>
     *   <li>未定义分配 → fail-open 返回当前全量 Agent 目录（向后兼容）。</li>
     * </ul>
     */
    public Set<String> resolveAllowed(String tenantId) {
        TenantAgentAllocation allocation = configCenter.getTenantAllocation();
        Optional<Set<String>> resolved = allocation.resolve(tenantId);
        if (resolved.isPresent()) {
            return Collections.unmodifiableSet(new HashSet<>(resolved.get()));
        }
        // 未定义分配：fail-open 返回全量 Agent 目录
        Set<String> all = new HashSet<>();
        for (AgentSpec spec : configCenter.listAgentSpecs()) {
            all.add(spec.identity().agentId());
        }
        return Collections.unmodifiableSet(all);
    }

    /** 断言租户可取用该 Agent；越权抛出 {@link NoSuchAgentException}（上游结算 NO_AGENT）。 */
    public void assertAllowed(String tenantId, String agentId) {
        if (!resolveAllowed(tenantId).contains(agentId)) {
            throw new NoSuchAgentException(agentId);
        }
    }
}
