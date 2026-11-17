package io.yunxi.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import io.yunxi.app.shard.AgentShardResolver;
import io.yunxi.app.startup.StartupGate;
import io.yunxi.capability.McpDirectory;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.AgentConfigurer;
import io.yunxi.runtime.AgentDefinitionLoader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * MCP 构建期失败 → 实例换装自愈的聚焦单测：
 * 构建（布建）期 MCP 服务器不可达时登记待换装；共享连接恢复可达后触发 Agent 实例换装，
 * 新实例（含重新派生的工具集与权限上下文）替换四处注册真相源，并清空待换装登记。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentProvisionerRecoveryTest {

    private final AgentDefinitionLoader loader = mock(AgentDefinitionLoader.class);
    private final AgentConfigurer configurer = mock(AgentConfigurer.class);
    private final AguiAgentRegistry agui = mock(AguiAgentRegistry.class);
    private final A2aServerRegistry a2a = mock(A2aServerRegistry.class);
    private final HarnessGateway gateway = mock(HarnessGateway.class);
    private final McpDirectory mcpDirectory = mock(McpDirectory.class);
    private final StartupGate startupGate = mock(StartupGate.class);
    private final AgentShardResolver shardResolver = mock(AgentShardResolver.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentRegistry> adminRegistry = mock(ObjectProvider.class);

    private final HarnessAgent agentA = mock(HarnessAgent.class);
    private final HarnessAgent agentA2 = mock(HarnessAgent.class);

    private AgentProvisioner provisioner() {
        when(agentA.getName()).thenReturn("A");
        when(agentA2.getName()).thenReturn("A2");
        when(startupGate.tryAcquire()).thenReturn(true);
        when(loader.loadAllSpecs()).thenReturn(List.of(spec()));
        when(shardResolver.owns("a")).thenReturn(true);
        when(configurer.configureDetailed(any()))
                .thenReturn(new AgentConfigurer.ProvisionedAgent(agentA, List.of("mcp1")))
                .thenReturn(new AgentConfigurer.ProvisionedAgent(agentA2, List.of()));
        return new AgentProvisioner(loader, configurer, agui, a2a, gateway, List.of(),
                adminRegistry, startupGate, shardResolver, mcpDirectory,
                "localhost", 8080, "/a2a");
    }

    private static AgentSpec spec() {
        return new AgentSpec(
                new AgentSpec.Identity("a", "A", "test"),
                null, null, null, null, null, new AgentSpec.SubagentsConfig(List.of()), null);
    }

    @Test
    void provisionThenProbeRebuildSwapsAgentAcrossRegistries() {
        AgentProvisioner p = provisioner();

        // 布建：构建期 mcp1 不可达，登记待换装
        p.provision();
        verify(gateway).registerAgent("a", agentA);
        verify(gateway).bindMainAgent(agentA);

        // 探活：mcp1 不可达 → 不换装
        when(mcpDirectory.probe("mcp1")).thenReturn(false);
        p.probeAndRebuild();
        verify(gateway, times(1)).registerAgent(any(), any());

        // 探活：mcp1 恢复 → 换装，新实例替换四处真相源，主 Agent 重新绑定
        when(mcpDirectory.probe("mcp1")).thenReturn(true);
        p.probeAndRebuild();
        verify(gateway).registerAgent("a", agentA2);
        verify(gateway).bindMainAgent(agentA2);
        verify(a2a).evict("a");

        // 恢复完成：待换装清空，再次探活不重复重建
        p.probeAndRebuild();
        verify(configurer, times(2)).configureDetailed(any());
    }

    @Test
    void probeDoesNotRebuildWhenProbeStillFails() {
        AgentProvisioner p = provisioner();

        p.provision();
        when(mcpDirectory.probe("mcp1")).thenReturn(false);
        p.probeAndRebuild();

        // 目标仍不可达：不得换装（保留待换装登记，下轮再探）
        verify(configurer, times(1)).configureDetailed(any());
        verify(gateway, times(1)).registerAgent(any(), any());
    }
}
