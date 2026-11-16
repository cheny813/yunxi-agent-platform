package io.yunxi.app.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.config.ConfigService;
import io.yunxi.config.TenantAgentAllocation;
import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.Tenant;
import io.yunxi.runtime.Json;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link NacosBackedConfigCenter}：Nacos 作为 Agent 定义共享存储时的读写、Redis 共享读缓存、
 * 内存镜像同步，以及 Nacos / Redis 不可达时优雅降级。使用 mock ConfigService / StringRedisTemplate，不依赖真实 Nacos/Redis。
 */
class NacosBackedConfigCenterTest {

    private ConfigCenterNacosProperties props() {
        ConfigCenterNacosProperties p = new ConfigCenterNacosProperties();
        p.setDataId("yunxi.agent-definitions.json");
        p.setGroup("YUNXI_CONFIG_GROUP");
        return p;
    }

    private AgentSpec spec(String id) {
        return new AgentSpec(
                new AgentSpec.Identity(id, id + "-name", "desc"),
                null, null,
                new AgentSpec.StateConfig("memory", true),
                null, null, null, null);
    }

    private String cacheKey() {
        return "yunxi:config-center:yunxi.agent-definitions.json";
    }

    @Test
    void registerUpdatesImageAndPublishesToNacos() throws Exception {
        ConfigService cs = mock(ConfigService.class);
        when(cs.getConfig(anyString(), anyString(), anyLong())).thenReturn(null);
        NacosBackedConfigCenter center = new NacosBackedConfigCenter(cs, props());

        AgentSpec a1 = spec("a1");
        center.registerAgentSpec(a1);

        assertSame(a1, center.getAgentSpec("a1"));
        Collection<AgentSpec> all = center.listAgentSpecs();
        assertTrue(all.contains(a1));
        assertTrue(center.containsAgentSpec("a1"));
        verify(cs).publishConfig(eq("yunxi.agent-definitions.json"), eq("YUNXI_CONFIG_GROUP"), anyString());
    }

    @Test
    void sharedAcrossInstancesReflectsWhetherWritesReallyLeaveThisInstance() throws Exception {
        ConfigService cs = mock(ConfigService.class);
        when(cs.getConfig(anyString(), anyString(), anyLong())).thenReturn(null);

        assertTrue(new NacosBackedConfigCenter(cs, props()).isSharedAcrossInstances(),
                "已连上共享存储：写入跨实例可见");

        assertFalse(new NacosBackedConfigCenter(null, props()).isSharedAcrossInstances(),
                "已配置 Nacos 但连接失败退化为内存时，写入不跨实例可见，不得声称共享");
    }

    @Test
    void removeUpdatesImageAndPublishesToNacos() throws Exception {
        ConfigService cs = mock(ConfigService.class);
        when(cs.getConfig(anyString(), anyString(), anyLong())).thenReturn(null);
        NacosBackedConfigCenter center = new NacosBackedConfigCenter(cs, props());

        AgentSpec a1 = spec("a1");
        center.registerAgentSpec(a1);
        center.removeAgentSpec("a1");

        assertFalse(center.containsAgentSpec("a1"));
        verify(cs, atLeast(2)).publishConfig(anyString(), anyString(), anyString());
    }

    @Test
    void unknownAgentThrowsWhenNotRegistered() {
        ConfigService cs = mock(ConfigService.class);
        NacosBackedConfigCenter center = new NacosBackedConfigCenter(cs, props());
        assertThrows(IllegalArgumentException.class, () -> center.getAgentSpec("missing"));
    }

    @Test
    void degradesToMemoryWhenNacosUnavailable() {
        NacosBackedConfigCenter center = new NacosBackedConfigCenter(null, props());

        AgentSpec a1 = spec("a1");
        center.registerAgentSpec(a1);

        assertSame(a1, center.getAgentSpec("a1"));
        assertTrue(center.containsAgentSpec("a1"));
    }

    @Test
    void registerWritesToRedisSharedCache() throws Exception {
        ConfigService cs = mock(ConfigService.class);
        when(cs.getConfig(anyString(), anyString(), anyLong())).thenReturn(null);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);

        NacosBackedConfigCenter center = new NacosBackedConfigCenter(cs, props(), redis);
        center.registerAgentSpec(spec("a1"));

        verify(ops).set(eq(cacheKey()), anyString());
    }

    @Test
    void startupHydratesImageFromRedisCache_skipsNacos() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        AgentSpec a1 = spec("a1");
        String agentJson = Json.objectMapper().writeValueAsString(List.of(a1));
        String allocJson = Json.objectMapper().writeValueAsString(new TenantAgentAllocation(Map.of(), Set.of()));
        ConfigCenterNacosProperties p = props();
        // Agent 定义快照与分配快照均从 Redis hydrate：镜像从 Redis 来，不再回源 Nacos（避免 getConfig 调用）。
        when(ops.get(eq("yunxi:config-center:" + p.getDataId()))).thenReturn(agentJson);
        when(ops.get(eq("yunxi:config-center:" + p.getAllocationDataId()))).thenReturn(allocJson);

        ConfigService cs = mock(ConfigService.class);
        NacosBackedConfigCenter center = new NacosBackedConfigCenter(cs, p, redis);

        AgentSpec got = center.getAgentSpec("a1");
        assertNotNull(got);
        assertEquals("a1", got.identity().agentId());
        // 仅断言 Agent 定义快照未回源 Nacos（tenant 快照回源 yunxi:tenants 是预期的热加载行为）。
        verify(cs, never()).getConfig(eq(p.getDataId()), anyString(), anyLong());
    }

    @Test
    void redisWriteFailure_doesNotThrow() throws Exception {
        ConfigService cs = mock(ConfigService.class);
        when(cs.getConfig(anyString(), anyString(), anyLong())).thenReturn(null);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        doThrow(new RuntimeException("redis down")).when(ops).set(anyString(), anyString());

        NacosBackedConfigCenter center = new NacosBackedConfigCenter(cs, props(), redis);
        AgentSpec a1 = spec("a1");
        assertDoesNotThrow(() -> center.registerAgentSpec(a1));
        assertSame(a1, center.getAgentSpec("a1"));
    }

    @Test
    void registerTenant_publishesToNacosAndBackfillsRedis() throws Exception {
        ConfigService cs = mock(ConfigService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);

        NacosBackedConfigCenter center = new NacosBackedConfigCenter(cs, props(), redis);
        Tenant t = Tenant.active("t1", "Tenant One");
        center.registerTenant(t);

        // 租户定义经独立 dataId 持久化到 Nacos，并同步回填 Redis 共享读缓存。
        verify(cs).publishConfig(eq("yunxi:tenants"), eq(props().getGroup()), anyString());
        verify(ops).set(eq("yunxi:config-center:yunxi:tenants"), anyString());
        // 写入后本地镜像立即可读。
        Optional<Tenant> got = center.getTenant("t1");
        assertTrue(got.isPresent());
        assertEquals("t1", got.get().tenantId());
    }

    @Test
    void getTenant_missingReturnsEmpty() {
        ConfigService cs = mock(ConfigService.class);
        NacosBackedConfigCenter center = new NacosBackedConfigCenter(cs, props());
        assertFalse(center.getTenant("ghost").isPresent());
    }
}
