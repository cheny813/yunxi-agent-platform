package io.yunxi.runtime;

import io.yunxi.runtime.tenant.TenantConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeModelProvider;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.yunxi.capability.McpDirectory;
import io.yunxi.capability.ModelRoutingPolicy;
import io.yunxi.capability.RoutedModel;
import io.yunxi.config.ConfigCenter;
import io.yunxi.config.ResolvedConfig;
import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.AgentSpecValidator;
import io.yunxi.runtime.middleware.SessionToolGroupRegistry;
import io.yunxi.runtime.permission.PermissionTemplateResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 真实大模型联调验证（默认禁用，仅当 {@code -Dreal.llm=true} 时激活）。
 *
 * <p>与 {@link PermissionContextCallIntegrationTest}（固定文本模型）互补：本测试用 AgentScope-Java
 * 原生 DashScope provider 驱动一次真实 {@code call}，确认「yunxi 装配 + 真实大模型链路」端到端可行，
 * 且构建期权限模板在真实对话下也经 freshState 进入运行时会话状态、派生默认（DEFAULT）模式。</p>
 *
 * <p>安全：模型 key 仅从环境变量 {@code DASHSCOPE_API_KEY} 读取（DashScopeModelProvider 原生支持），
 * 不进入任何源码 / 配置文件；模型名由 {@code -Dllm.model} 指定，缺省 {@code qwen3.8-27b}。
 * 本测试默认禁用，避免 CI 误触真实网络与凭证。</p>
 */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "real.llm", matches = "true")
class PermissionContextRealLlmCallTest {

    @Test
    void configureThenRealCall_appliesPermissionTemplateIntoSessionState() {
        // 带权限配置：toolGate 启用 shell → 依据权限模板派生 PermissionMode.DEFAULT
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-perm-realllm", "Real LLM Agent", "e2e with real model"),
                null, null, null,
                new AgentSpec.PermissionConfig(
                        new AgentSpec.PermissionConfig.ToolGate(true, List.of("shell")),
                        null, null, null),
                null, new AgentSpec.SubagentsConfig(List.of()), null);

        ConfigCenter configCenter = mock(ConfigCenter.class);
        McpDirectory mcpDirectory = mock(McpDirectory.class);
        ModelRoutingPolicy routingPolicy = mock(ModelRoutingPolicy.class);
        ModelFactory modelFactory = mock(ModelFactory.class);
        DistributedRuntimeConfig distributedRuntimeConfig = mock(DistributedRuntimeConfig.class);
        AgentSpecValidator specValidator = mock(AgentSpecValidator.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        when(specValidator.validate(any())).thenReturn(AgentSpec.ValidationResult.ok());
        when(configCenter.resolve(any())).thenReturn(mock(ResolvedConfig.class));
        when(routingPolicy.route(any(), any(), any()))
                .thenReturn(new RoutedModel("real-llm-model", List.of()));
        String modelName = System.getProperty("llm.model", "qwen3.8-27b");
        when(modelFactory.create(anyString()))
                .thenReturn(new DashScopeModelProvider().create(modelName));
        DistributedStore store = DistributedStore.builder()
                .agentStateStore(new InMemoryAgentStateStore())
                .baseStore(mock(BaseStore.class))
                .sessionTurnGate(new LocalSessionTurnGate())
                .build();
        when(distributedRuntimeConfig.distributedStore()).thenReturn(store);

        AgentConfigurer configurer = new AgentConfigurer(
                configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                List.of(), List.of(), List.of(), eventPublisher, List.of(),
                new SessionToolGroupRegistry(), new PermissionTemplateResolver(), new MemoryProperties(),
                ".agentscope/workspace/skills", new TenantConfig(false, "default"));

        HarnessAgent agent = configurer.configure(spec);

        String userId = "u-perm-realllm";
        String sessionId = "s-perm-realllm";
        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        Msg reply = agent.call("你好,请只回复一句简短问候。", ctx).block();
        assertNotNull(reply, "真实大模型 call 应返回回复消息");
        System.out.println("[real-llm] reply text = " + reply.getTextContent());

        PermissionMode mode = agent.getDelegate().getPermissionMode(userId, sessionId);
        assertEquals(PermissionMode.DEFAULT, mode,
                "真实对话下,构建期权限模板须经 freshState 进入运行时会话状态并派生 DEFAULT 模式");
    }
}
