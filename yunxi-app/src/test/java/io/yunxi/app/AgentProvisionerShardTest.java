package io.yunxi.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.yunxi.app.A2aServerRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import io.yunxi.app.shard.AgentShardResolver;
import io.yunxi.app.startup.StartupGate;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.AgentConfigurer;
import io.yunxi.runtime.AgentDefinitionLoader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 断言 {@link AgentProvisioner} 按 {@link AgentShardResolver} 的 owns 过滤，仅 owned agent 注册四处真相源。
 */
class AgentProvisionerShardTest {

    @Test
    void provisionsOnlyOwnedAgents() throws Exception {
        AgentSpec specA = mock(AgentSpec.class);
        when(specA.identity()).thenReturn(new AgentSpec.Identity("a", "na", "da"));
        AgentSpec specB = mock(AgentSpec.class);
        when(specB.identity()).thenReturn(new AgentSpec.Identity("b", "nb", "db"));

        HarnessAgent agentA = mock(HarnessAgent.class);
        HarnessAgent agentB = mock(HarnessAgent.class);

        AgentDefinitionLoader loader = mock(AgentDefinitionLoader.class);
        when(loader.loadAllSpecs()).thenReturn(List.of(specA, specB));
        AgentConfigurer configurer = mock(AgentConfigurer.class);
        when(configurer.configure(specA)).thenReturn(agentA);
        when(configurer.configure(specB)).thenReturn(agentB);

        AguiAgentRegistry agui = mock(AguiAgentRegistry.class);
        A2aServerRegistry a2a = mock(A2aServerRegistry.class);
        HarnessGateway gateway = mock(HarnessGateway.class);
        org.springframework.beans.factory.ObjectProvider<AgentRegistry> regProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        StartupGate gate = mock(StartupGate.class);
        when(gate.tryAcquire()).thenReturn(true);

        AgentShardResolver resolver = mock(AgentShardResolver.class);
        when(resolver.owns("a")).thenReturn(true);
        when(resolver.owns("b")).thenReturn(false);

        AgentProvisioner provisioner = new AgentProvisioner(
                loader, configurer, agui, a2a, gateway, List.of(), regProvider, gate, resolver,
                "localhost", 8080, "/a2a");
        provisioner.provision();

        verify(agui).registerFactory(eq("a"), any());
        verify(agui, never()).registerFactory(eq("b"), any());
        verify(a2a).registerFactory(eq("a"), any());
        verify(a2a, never()).registerFactory(eq("b"), any());
        verify(gateway).registerAgent(eq("a"), any());
        verify(gateway, never()).registerAgent(eq("b"), any());
    }
}
