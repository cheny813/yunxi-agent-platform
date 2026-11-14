package io.yunxi.config;

import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.Tenant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置平面。内存版 {@link ConfigCenter}，作为默认实现与测试使用。
 * 解析 {@link AgentSpec} 时复用接口默认 {@link ConfigCenter#resolve}（模型由 ModelRoutingPolicy 按 agentId
 * 路由解析，不在此填充），本类不再重复实现。
 */
public class InMemoryConfigCenter implements ConfigCenter {

    private final Map<String, ConfigFragment> fragments = new ConcurrentHashMap<>();
    private final Map<String, AgentSpec> agentSpecs = new ConcurrentHashMap<>();
    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
    private final FragmentValidator validator;
    private volatile TenantAgentAllocation allocation = new TenantAgentAllocation(Map.of(), Set.of());
    private final Object allocationLock = new Object();

    public InMemoryConfigCenter(FragmentValidator validator) {
        this.validator = validator;
    }

    public InMemoryConfigCenter() {
        this(new DefaultFragmentValidator());
    }

    /** 校验后写入片段表（按 id 覆盖）。 */
    @Override
    public void registerFragment(ConfigFragment fragment) {
        validator.validate(fragment);
        fragments.put(fragment.id(), fragment);
    }

    /** 按 id 读取片段；未知 id 抛 {@link IllegalArgumentException}。 */
    @Override
    public ConfigFragment getFragment(String id) {
        ConfigFragment fragment = fragments.get(id);
        if (fragment == null) {
            throw new IllegalArgumentException("unknown fragment: " + id);
        }
        return fragment;
    }

    /** 按 agentId 写入（或覆盖）Agent 定义。 */
    @Override
    public void registerAgentSpec(AgentSpec spec) {
        agentSpecs.put(spec.identity().agentId(), spec);
    }

    /** 按 agentId 读取 Agent 定义；未知 id 抛 {@link IllegalArgumentException}。 */
    @Override
    public AgentSpec getAgentSpec(String agentId) {
        AgentSpec spec = agentSpecs.get(agentId);
        if (spec == null) {
            throw new IllegalArgumentException("unknown agent spec: " + agentId);
        }
        return spec;
    }

    /** 返回全部已注册 Agent 定义（不可变快照）。 */
    @Override
    public Collection<AgentSpec> listAgentSpecs() {
        return List.copyOf(agentSpecs.values());
    }

    /** 按 agentId 移除 Agent 定义（若存在）。 */
    @Override
    public void removeAgentSpec(String agentId) {
        agentSpecs.remove(agentId);
    }

    /** 判断 agentId 是否已注册。 */
    @Override
    public boolean containsAgentSpec(String agentId) {
        return agentSpecs.containsKey(agentId);
    }

    /** 注册（覆盖）租户 → Agent 分配。 */
    @Override
    public void registerTenantAllocation(TenantAgentAllocation allocation) {
        synchronized (allocationLock) {
            this.allocation = allocation;
        }
    }

    /** 读取当前租户 → Agent 分配（未定义时为空分配）。 */
    @Override
    public TenantAgentAllocation getTenantAllocation() {
        return allocation;
    }

    /** 注册（覆盖）租户定义（按 tenantId 覆盖）。 */
    @Override
    public void registerTenant(Tenant tenant) {
        tenants.put(tenant.tenantId(), tenant);
    }

    /** 按 tenantId 读取租户定义；未知返回 empty。 */
    @Override
    public Optional<Tenant> getTenant(String tenantId) {
        return Optional.ofNullable(tenants.get(tenantId));
    }

    /** 返回全部已注册租户定义（不可变快照）。 */
    @Override
    public List<Tenant> listTenants() {
        return List.copyOf(tenants.values());
    }
}
