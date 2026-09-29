package io.yunxi.platform.ingress.agui;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * YunxiAguiAgentRegistryConfig 的单元测试（纯逻辑，不启动 Spring 上下文）。
 *
 * <p>覆盖：遍历 listAgents 时跳过 hidden agent；可见 agent 以不含 -agent 后缀的 agentId 注册；
 * factory 委托 AgentService.getAgentInstance 返回同一实例。</p>
 */
class YunxiAguiAgentRegistryConfigTest {

    private final YunxiAguiAgentRegistryConfig config = new YunxiAguiAgentRegistryConfig();

    @Test
    void registersVisibleAgentsExcludingHidden() {
        AgentService agentService = mock(AgentService.class);
        AgentInfoDto visible = new AgentInfoDto("alice", "p", "m", Instant.now());
        AgentInfoDto hidden = new AgentInfoDto("bob", "p", "m", Instant.now());
        hidden.setHidden(true);
        when(agentService.listAgents()).thenReturn(List.of(visible, hidden));

        Agent aliceAgent = mock(Agent.class);
        when(agentService.getAgentInstance("alice")).thenReturn(aliceAgent);

        AguiAgentRegistry registry = config.aguiAgentRegistry(agentService);

        Optional<Agent> resolved = registry.getAgent("alice");
        assertTrue(resolved.isPresent());
        assertSame(aliceAgent, resolved.get());
        assertFalse(registry.getAgent("bob").isPresent(), "hidden agent must not be registered");
        verify(agentService).getAgentInstance("alice");
        verify(agentService, never()).getAgentInstance("bob");
    }
}
