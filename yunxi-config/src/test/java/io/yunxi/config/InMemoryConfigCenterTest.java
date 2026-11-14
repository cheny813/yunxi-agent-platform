package io.yunxi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.yunxi.definition.AgentSpec;

/**
 * {@link InMemoryConfigCenter} 单元测试：片段注册时强制校验、按 id 读取、
 * Agent 规格注册 / 查询 / 移除，以及 {@code resolve} 把 {@code AgentSpec.state()}
 * 映射为扁平配置图（其余字段默认空）。
 */
class InMemoryConfigCenterTest {

    private static AgentSpec specWithState(String store, boolean persist) {
        return new AgentSpec(
                new AgentSpec.Identity("a1", "name", "desc"),
                null, null,
                new AgentSpec.StateConfig(store, persist),
                null, null,
                new AgentSpec.SubagentsConfig(List.of()), null);
    }

    private static AgentSpec specWithoutState() {
        return new AgentSpec(
                new AgentSpec.Identity("a1", "name", "desc"),
                null, null, null, null, null,
                new AgentSpec.SubagentsConfig(List.of()), null);
    }

    @Test
    void registerFragmentValidatesAndStores() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        ConfigFragment fragment = new ConfigFragment("f1", "model", Map.of("k", "v"));
        center.registerFragment(fragment);
        assertEquals(fragment, center.getFragment("f1"));
    }

    @Test
    void registerInvalidFragmentThrows() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        assertThrows(IllegalArgumentException.class,
                () -> center.registerFragment(new ConfigFragment("", "model", Map.of())));
    }

    @Test
    void getFragmentUnknownThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryConfigCenter().getFragment("missing"));
    }

    @Test
    void resolveMapsStateFields() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        ResolvedConfig resolved = center.resolve(specWithState("redis", true));
        assertEquals(Map.of("store", "redis", "persist", true), resolved.state());
        // 未提供模型配置的规格，解析后 primaryModel 为 null。
        assertNull(resolved.primaryModel());
    }

    @Test
    void resolveWithNullStateYieldsEmptyMap() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        ResolvedConfig resolved = center.resolve(specWithoutState());
        assertEquals(Map.of(), resolved.state());
    }

    @Test
    void registerAndLookupAgentSpec() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        AgentSpec spec = specWithState("redis", true);
        center.registerAgentSpec(spec);
        assertTrue(center.containsAgentSpec("a1"));
        assertEquals(spec, center.getAgentSpec("a1"));
        assertEquals(1, center.listAgentSpecs().size());
    }

    @Test
    void removeAgentSpec() {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        center.registerAgentSpec(specWithState("redis", true));
        center.removeAgentSpec("a1");
        assertFalse(center.containsAgentSpec("a1"));
    }

    @Test
    void getAgentSpecUnknownThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryConfigCenter().getAgentSpec("missing"));
    }
}
