package io.yunxi.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yunxi.config.ConfigCenter;
import io.yunxi.config.ConfigFragment;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.config.TenantAgentAllocation;
import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.DefaultAgentSpecValidator;
import io.yunxi.definition.Tenant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentDefinitionLoader}：classpath 发布时定义与配置中心内容的同步语义。
 *
 * <p>核心约束有两条，方向相反但必须同时成立：
 * <ul>
 *   <li>发布包中的定义变更<b>必须</b>覆盖配置中心里的旧内容——否则升级后提示词 / 权限模板 / 工具白名单
 *       的修改静默不生效（部署成功但行为没变）；</li>
 *   <li>内容一致时<b>不得</b>写入——多实例滚动重启会让每个实例都发布一次快照，触发无意义的热加载风暴。</li>
 * </ul>
 */
class AgentDefinitionLoaderTest {

    @Test
    void publishedDefinitionOverridesStaleConfigCenterCopy() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        new AgentDefinitionLoader(center, new DefaultAgentSpecValidator()).load();

        AgentSpec published = firstPublishedSpec(center);
        String agentId = published.identity().agentId();
        center.registerAgentSpec(withDescription(published, "配置中心里的旧版本（升级前）"));

        new AgentDefinitionLoader(center, new DefaultAgentSpecValidator()).load();

        assertEquals(published, center.getAgentSpec(agentId),
                "发布包中的定义应覆盖配置中心里的旧版本，保证升级后行为跟随版本");
    }

    @Test
    void identicalDefinitionIsNotWrittenAgain() {
        InMemoryConfigCenter source = new InMemoryConfigCenter();
        new AgentDefinitionLoader(source, new DefaultAgentSpecValidator()).load();
        Collection<AgentSpec> published = source.listAgentSpecs();

        CountingConfigCenter target = new CountingConfigCenter();
        published.forEach(target::registerAgentSpec);
        int writesBefore = target.writes();

        new AgentDefinitionLoader(target, new DefaultAgentSpecValidator()).load();

        assertEquals(writesBefore, target.writes(),
                "内容一致时应零写入，避免多实例滚动重启反复发布快照触发热加载风暴");
    }

    @Test
    void runtimeDefinedAgentIsLeftUntouched() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        AgentSpec runtimeDefined = withId(firstPublishedSpecAfterSeed(center), "runtime-only-agent");
        center.registerAgentSpec(runtimeDefined);

        new AgentDefinitionLoader(center, new DefaultAgentSpecValidator()).load();

        assertEquals(runtimeDefined, center.getAgentSpec("runtime-only-agent"),
                "非发布时定义的 Agent 不属于加载器管辖，不得被改动");
    }

    /** 先跑一次加载以取到真实的 classpath 定义，再据此构造测试用变体。 */
    private AgentSpec firstPublishedSpecAfterSeed(InMemoryConfigCenter center) {
        new AgentDefinitionLoader(center, new DefaultAgentSpecValidator()).load();
        return firstPublishedSpec(center);
    }

    private AgentSpec firstPublishedSpec(InMemoryConfigCenter center) {
        return center.listAgentSpecs().stream()
                .sorted(Comparator.comparing(spec -> spec.identity().agentId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("测试依赖 classpath 中至少一份 agent-definitions/*.yml"));
    }

    private AgentSpec withDescription(AgentSpec spec, String description) {
        return new AgentSpec(
                new AgentSpec.Identity(spec.identity().agentId(), spec.identity().name(), description),
                spec.skill(), spec.mcp(), spec.state(), spec.permission(), spec.toolkit(),
                spec.subagents(), spec.sandbox(), spec.schedule(), spec.plan(), spec.intent());
    }

    private AgentSpec withId(AgentSpec spec, String agentId) {
        return new AgentSpec(
                new AgentSpec.Identity(agentId, spec.identity().name(), spec.identity().description()),
                spec.skill(), spec.mcp(), spec.state(), spec.permission(), spec.toolkit(),
                spec.subagents(), spec.sandbox(), spec.schedule(), spec.plan(), spec.intent());
    }

    /** 记录写入次数的配置中心替身，用于断言"零写入"。 */
    private static final class CountingConfigCenter implements ConfigCenter {

        private final InMemoryConfigCenter delegate = new InMemoryConfigCenter();
        private int writes;

        int writes() {
            return writes;
        }

        @Override
        public void registerAgentSpec(AgentSpec spec) {
            writes++;
            delegate.registerAgentSpec(spec);
        }

        @Override
        public void registerFragment(ConfigFragment fragment) {
            delegate.registerFragment(fragment);
        }

        @Override
        public ConfigFragment getFragment(String id) {
            return delegate.getFragment(id);
        }

        @Override
        public AgentSpec getAgentSpec(String agentId) {
            return delegate.getAgentSpec(agentId);
        }

        @Override
        public Collection<AgentSpec> listAgentSpecs() {
            return delegate.listAgentSpecs();
        }

        @Override
        public void removeAgentSpec(String agentId) {
            delegate.removeAgentSpec(agentId);
        }

        @Override
        public boolean containsAgentSpec(String agentId) {
            return delegate.containsAgentSpec(agentId);
        }

        @Override
        public void registerTenantAllocation(TenantAgentAllocation allocation) {
            delegate.registerTenantAllocation(allocation);
        }

        @Override
        public TenantAgentAllocation getTenantAllocation() {
            return delegate.getTenantAllocation();
        }

        @Override
        public void registerTenant(Tenant tenant) {
            delegate.registerTenant(tenant);
        }

        @Override
        public Optional<Tenant> getTenant(String tenantId) {
            return delegate.getTenant(tenantId);
        }

        @Override
        public List<Tenant> listTenants() {
            return delegate.listTenants();
        }
    }
}
