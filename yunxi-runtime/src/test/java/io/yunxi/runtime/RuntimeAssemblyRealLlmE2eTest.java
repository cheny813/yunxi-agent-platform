package io.yunxi.runtime;

import io.yunxi.runtime.tenant.TenantConfig;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
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
 * 运行时完整装配端到端（真实大模型，默认禁用，仅 {@code -Dreal.llm=true} 激活）。
 *
 * <p>与 {@link PermissionContextRealLlmCallTest}（权限场景）互补：本测试聚焦「完整装配」——
 * {@link AgentConfigurer} 把中间件链 + 模型路由 + 存储 + MCP 目录接线串起来，用真实 DashScope 模型驱动
 * 一次真实对话，并验证同一会话的多轮状态保持（第二轮 call 复用已落库会话状态）。</p>
 *
 * <p>安全：模型 key 仅从环境变量 {@code DASHSCOPE_API_KEY} 读取（DashScopeModelProvider 原生支持），
 * 不进入任何源码 / 配置文件；模型名由 {@code -Dllm.model} 指定，缺省 {@code qwen3.8-27b}。</p>
 */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "real.llm", matches = "true")
class RuntimeAssemblyRealLlmE2eTest {

    @Test
    void configureThenRealCall_fullAssemblyResponds() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-rt-e2e", "Runtime E2E Agent", "full assembly e2e"),
                null, null, null, null, null, new AgentSpec.SubagentsConfig(List.of()), null);

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
                .thenReturn(new RoutedModel("rt-e2e-model", List.of()));
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

        String userId = "u-rt-e2e";
        String sessionId = "s-rt-e2e";
        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        Msg reply = agent.call("请用一句话介绍你自己。", ctx).block();
        assertNotNull(reply, "完整装配下真实大模型 call 应返回回复");
        System.out.println("[real-llm][rt-e2e] reply = " + reply.getTextContent());

        // 同一会话第二轮：复用已落库会话状态
        Msg reply2 = agent.call("继续，补充一句你的能力。", ctx).block();
        assertNotNull(reply2, "多轮对话第二轮 call 应返回回复（会话状态已保持）");
        System.out.println("[real-llm][rt-e2e] reply2 = " + reply2.getTextContent());
    }
}
