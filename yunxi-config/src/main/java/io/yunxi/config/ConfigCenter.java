package io.yunxi.config;

import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.Tenant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 配置平面。每一个配置片段的唯一拥有者，也是唯一可以把 {@link AgentSpec} 解析为 {@link ResolvedConfig} 的组件。
 * 同时是 Agent 定义的统一真相源：所有 Agent 定义均存于此处，支持热加载与多实例共享。
 * 当前唯一生产写入方是发布包加载器；统一写入接口保留给管理端 / 运维写入路径。
 */
public interface ConfigCenter {

    /**
     * 写入是否跨实例可见（后端是否为共享存储）。
     *
     * <p>默认 {@code false}：进程内实现的写入只在本实例生效。共享后端只有在<b>真正连上</b>共享存储时才返回
     * {@code true}——「已配置但连接失败而退化为内存」按不可共享对待，否则调用方会误以为写入已持久化到共享存储，
     * 而实际上各实例的定义目录会静默分叉。
     */
    default boolean isSharedAcrossInstances() {
        return false;
    }

    /** 注册一个配置片段；片段结构与 id / kind / content 齐备性由 {@link FragmentValidator} 校验。 */
    void registerFragment(ConfigFragment fragment);

    /** 按 id 读取片段；不存在时抛 {@link IllegalArgumentException}。 */
    ConfigFragment getFragment(String id);

    /**
     * 将一个 {@link AgentSpec} 解析为运行时可直接消费的 {@link ResolvedConfig}。
     * 纯本地解析（仅依赖 AgentSpec 内联字段，不依赖存储后端），故抽为默认方法，
     * 所有实现（内存 / Nacos）共用，避免每个后端重复实现。
     */
    default ResolvedConfig resolve(AgentSpec spec) {
        Map<String, Object> state = spec.state() != null
                ? Map.of("store", spec.state().store(), "persist", spec.state().persist())
                : Map.of();
        // 模型不再由 AgentSpec 携带（定义平面统一后纯由 ModelRoutingPolicy 按 agentId 路由解析），故不在此填充。
        // routing / observability 原由 AgentSpec 的 routingPolicyRef / observabilityRef 解析，该引用机制已移除
        // （策略体从未被注册或消费），故 ResolvedConfig 不再携带这两项。
        return new ResolvedConfig(null, List.of(), null, state);
    }

    /** 注册（或覆盖）一个 Agent 定义，是所有 Agent 定义的统一写入入口。 */
    void registerAgentSpec(AgentSpec spec);

    /** 按 agentId 读取 Agent 定义；不存在时抛 {@link IllegalArgumentException}。 */
    AgentSpec getAgentSpec(String agentId);

    /** 列出当前注册的全部 Agent 定义（不可变快照）。 */
    Collection<AgentSpec> listAgentSpecs();

    /** 按 agentId 移除一个 Agent 定义（若存在）。 */
    void removeAgentSpec(String agentId);

    /** 判断指定 agentId 是否已注册。 */
    boolean containsAgentSpec(String agentId);

    /**
     * 注册（或覆盖）租户 → Agent 分配，是分配的单一写入入口（共享真相源）。
     * Nacos 实现持久化到<b>独立 dataId</b>（与 AgentSpec 快照隔离），保证多实例一致；
     * Nacos 关闭时降级内存，不抛错。
     */
    void registerTenantAllocation(TenantAgentAllocation allocation);

    /**
     * 读取当前租户 → Agent 分配；未定义时返回空分配（{@code entries} 与 {@code defaultAgents} 均为空）。
     */
    TenantAgentAllocation getTenantAllocation();

    /**
     * 注册（或覆盖）一个租户定义，是租户生命周期的单一写入入口（共享真相源）。
     * Nacos 实现持久化到<b>独立 dataId</b>（与 AgentSpec / 分配快照隔离），保证多实例一致；
     * Nacos 关闭时降级内存，不抛错。
     */
    void registerTenant(Tenant tenant);

    /**
     * 按 tenantId 读取租户定义；不存在时返回 {@link Optional#empty()}（fail-open 依据）。
     */
    Optional<Tenant> getTenant(String tenantId);

    /** 列出当前注册的全部租户定义（不可变快照）。 */
    List<Tenant> listTenants();
}
