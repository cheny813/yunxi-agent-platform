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
 * 权限上下文强验证：端到端跑通「{@link AgentConfigurer#configure(AgentSpec)} 构建期接线
 * → 发起一次真实 {@code call}（新会话）→ 模板经框架 freshState 写入会话状态」。
 *
 * <p>弱验证（{@code AgentConfigurerTest}）只证明 {@code PermissionTemplateResolver.resolve} 被调用一次；
 * 本测试进一步证明：构建期产出的权限模板确实进入运行时会话状态，且派生模式与权限派生规范一致。
 * 验证入口采用委托实例的内存态 {@code getPermissionMode(userId, sessionId)}，与持久化存储无关，
 * 避免 mock 存储干扰（权限默认上下文规范：未调用前的 Agent 返回默认空 trivial 上下文，必须用新会话验证）。</p>
 */
class PermissionContextCallIntegrationTest {

    @Test
    void configureThenCall_appliesPermissionTemplateIntoSessionState() {
        // 带权限配置：toolGate 启用 shell → 依据权限模板派生 PermissionMode.DEFAULT
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-perm-call", "Perm Call Agent", "strong verification"),
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
                .thenReturn(new RoutedModel("unit-test-model", List.of()));
        when(modelFactory.create(anyString())).thenReturn(new StubTextModel("done"));
        DistributedStore store = DistributedStore.builder()
                .agentStateStore(new InMemoryAgentStateStore())
                .baseStore(mock(BaseStore.class))
                .sessionTurnGate(new LocalSessionTurnGate())
                .build();
        when(distributedRuntimeConfig.distributedStore()).thenReturn(store);

        AgentConfigurer configurer = stubObjectProviders(new AgentConfigurer(
                configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                List.of(), List.of(), List.of(), eventPublisher, List.of(),
                new SessionToolGroupRegistry(), new PermissionTemplateResolver(), new MemoryProperties(),
                ".agentscope/workspace/skills", new TenantConfig(false, "default")));

        HarnessAgent agent = configurer.configure(spec);

        // 全新 userId / sessionId 发起一次 call，触发 freshState 应用构建期模板
        String userId = "u-perm-strong";
        String sessionId = "s-perm-strong";
        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        Msg reply = agent.call("hello", ctx).block();
        assertNotNull(reply, "call 应返回回复消息");

        // 强验证：模板已进入会话状态，且派生模式为默认（DEFAULT）
        PermissionMode mode = agent.getDelegate().getPermissionMode(userId, sessionId);
        assertEquals(PermissionMode.DEFAULT, mode,
                "构建期权限模板须经 freshState 进入运行时会话状态，并派生 DEFAULT 模式");
    }

    /**
     * 纯单元测试不经 Spring 容器，{@code @Autowired ObjectProvider} 字段为 null；
     * 注入空 provider mock（{@code getIfAvailable()} 返回 null），对应可选能力默认关闭的生产行为。
     */
    private static AgentConfigurer stubObjectProviders(AgentConfigurer configurer) {
        for (String field : new String[] {"gitSkillRepositoryProvider", "jdbcSkillRepositoryProvider",
                "tenantStorageMeterProvider", "messageBusProvider"}) {
            try {
                java.lang.reflect.Field f = AgentConfigurer.class.getDeclaredField(field);
                f.setAccessible(true);
                f.set(configurer, mock(org.springframework.beans.factory.ObjectProvider.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("注入 ObjectProvider 字段失败: " + field, e);
            }
        }
        return configurer;
    }
}
