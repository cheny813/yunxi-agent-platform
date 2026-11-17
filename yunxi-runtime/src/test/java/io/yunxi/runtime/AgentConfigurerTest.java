package io.yunxi.runtime;

import io.yunxi.runtime.tenant.TenantConfig;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.yunxi.capability.McpDirectory;
import io.yunxi.capability.McpServerSpec;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import org.springframework.context.ApplicationEventPublisher;
import java.lang.reflect.Field;

/**
 * 针对 {@link AgentConfigurer#configure(AgentSpec)} 的聚焦单测，锁定分布式状态存储的透传接线：
 * {@link DistributedRuntimeConfig} 提供的 {@link DistributedStore} 须经 HarnessAgent.fromAgent 包裹
 * 后透传并接线，且 build() 须据此自动装配委托实例的状态存储。
 *
 * <p>正确性轴：AgentScope-Java 2.1.0-BETA1 的 HarnessAgent.Builder 已暴露
 * {@code conflictPolicy(ConflictPolicy)} 入口，且 {@code fromAgent} 传播该策略；{@code configure} 经
 * {@code .conflictPolicy(ConflictPolicy.FAIL)} 将正确性轴设为 FAIL（冲突即抛异常跳过写入，而非静默
 * OVERWRITE），须与本平台已装配的分布式会话 turnGate 配套。本测试断言 conflictPolicy 已透传为 FAIL。
 */
@ExtendWith(MockitoExtension.class)
class AgentConfigurerTest {

    @Mock
    ConfigCenter configCenter;
    @Mock
    McpDirectory mcpDirectory;
    @Mock
    ModelRoutingPolicy routingPolicy;
    @Mock
    ModelFactory modelFactory;
    @Mock
    DistributedRuntimeConfig distributedRuntimeConfig;
    @Mock
    AgentSpecValidator specValidator;
    @Mock
    ApplicationEventPublisher eventPublisher;

    private static AgentSpec sampleSpec() {
        return new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "unit test agent"),
                null, null, null, null, null, new AgentSpec.SubagentsConfig(List.of()), null);
    }

    private static AgentConfigurer configurer(ConfigCenter configCenter,
            McpDirectory mcpDirectory,
            ModelRoutingPolicy routingPolicy,
            ModelFactory modelFactory,
            DistributedRuntimeConfig distributedRuntimeConfig,
            AgentSpecValidator specValidator,
            ApplicationEventPublisher eventPublisher,
            PermissionTemplateResolver permissionTemplateResolver) {
        return stubObjectProviders(new AgentConfigurer(
                configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                List.of(), List.of(), List.of(), eventPublisher, List.of(),
                new SessionToolGroupRegistry(), permissionTemplateResolver, new MemoryProperties(),
                ".agentscope/workspace/skills", new TenantConfig(false, "default")));
    }

    /**
     * 纯单元测试不经 Spring 容器，{@code @Autowired ObjectProvider} 字段为 null；
     * 注入空 provider mock（{@code getIfAvailable()} 返回 null），对应全部可选能力
     * （Git/JDBC 技能仓库、租户计量、事件总线）默认关闭的生产行为。
     */
    private static AgentConfigurer stubObjectProviders(AgentConfigurer configurer) {
        for (String field : new String[] {"gitSkillRepositoryProvider", "jdbcSkillRepositoryProvider",
                "tenantStorageMeterProvider", "messageBusProvider"}) {
            try {
                Field f = AgentConfigurer.class.getDeclaredField(field);
                f.setAccessible(true);
                f.set(configurer, mock(org.springframework.beans.factory.ObjectProvider.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("注入 ObjectProvider 字段失败: " + field, e);
            }
        }
        return configurer;
    }

    private static void stubCollaborators(AgentSpec spec,
            ConfigCenter configCenter,
            ModelRoutingPolicy routingPolicy,
            ModelFactory modelFactory,
            DistributedRuntimeConfig distributedRuntimeConfig,
            AgentSpecValidator specValidator,
            DistributedStore store) {
        when(specValidator.validate(any())).thenReturn(AgentSpec.ValidationResult.ok());
        when(configCenter.resolve(any())).thenReturn(mock(ResolvedConfig.class));
        RoutedModel routed = new RoutedModel("unit-test-model", List.of());
        when(routingPolicy.route(any(), any(), any())).thenReturn(routed);
        when(modelFactory.create(anyString())).thenReturn(mock(Model.class));
        when(distributedRuntimeConfig.distributedStore()).thenReturn(store);
    }

    private static DistributedStore buildStore() {
        return DistributedStore.builder()
                .agentStateStore(mock(AgentStateStore.class))
                .baseStore(mock(BaseStore.class))
                .sessionTurnGate(mock(SessionTurnGate.class))
                .build();
    }

    @Test
    void configure_wiresDistributedStoreAndStateStore() {
        AgentSpec spec = sampleSpec();
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        HarnessAgent agent = configurer(configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                eventPublisher, new PermissionTemplateResolver()).configure(spec);

        assertTrue(agent instanceof HarnessAgent);
        assertSame(store, agent.getDistributedStore(),
                "distributedStore 须从 DistributedRuntimeConfig 透传并接线");
        assertNotNull(agent.getStateStore(),
                "build() 须从 distributedStore 自动装配状态存储（委托实例）");
        assertEquals(ConflictPolicy.FAIL, agent.getConflictPolicy(),
                "正确性轴须设为 FAIL（冲突抛异常跳过写入，而非静默 OVERWRITE）");
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void configureDetailed_recordsFailedMcpServerWhenUnreachable() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-1", "Agent One", "unit test agent"),
                null, new AgentSpec.McpConfig(List.of("mcp1")), null, null, null,
                new AgentSpec.SubagentsConfig(List.of()), null);
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, null);
        McpClientWrapper unreachable = mock(McpClientWrapper.class);
        when(unreachable.getName()).thenReturn("mcp1");
        when(unreachable.initialize())
                .thenReturn(Mono.error(new RuntimeException("failed to initialize: connection refused")));
        when(mcpDirectory.get("mcp1"))
                .thenReturn(Optional.of(McpServerSpec.sse("mcp1", "http://unreachable")));
        when(mcpDirectory.buildClient(any())).thenReturn(unreachable);
        when(mcpDirectory.probe("mcp1")).thenReturn(false);

        AgentConfigurer.ProvisionedAgent provisioned = configurer(configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator, eventPublisher,
                new PermissionTemplateResolver()).configureDetailed(spec);

        assertTrue(provisioned.agent() instanceof HarnessAgent);
        assertTrue(provisioned.failedMcpServers().contains("mcp1"),
                "构建期不可达的 MCP 服务器必须进入 failedMcpServers（换装恢复依据）");
    }

    @Test
    void configureDetailed_noFailedServersWithoutMcpSubscription() {
        AgentSpec spec = sampleSpec();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, null);

        AgentConfigurer.ProvisionedAgent provisioned = configurer(configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator, eventPublisher,
                new PermissionTemplateResolver()).configureDetailed(spec);

        assertTrue(provisioned.failedMcpServers().isEmpty());
    }

    @Test
    void configure_withoutDistributedStore_skipsWiring() {
        AgentSpec spec = sampleSpec();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, null);

        HarnessAgent agent = configurer(configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                eventPublisher, new PermissionTemplateResolver()).configure(spec);

        assertNull(agent.getDistributedStore(),
                "distributedStore 为 null 时应优雅跳过接线");
    }

    @Test
    void configure_withSharedBaseStore_wiresRemoteWorkspaceFilesystem() {
        // 长期记忆（工作区 MEMORY.md）在多副本部署下必须落到共享存储，否则每实例各写一份即分叉。
        // 分布式后端提供 BaseStore 时，装配须把工作区文件系统切到框架的远程实现。
        AgentSpec spec = sampleSpec();
        BaseStore baseStore = mock(BaseStore.class);
        DistributedStore store = DistributedStore.builder()
                .agentStateStore(mock(AgentStateStore.class))
                .baseStore(baseStore)
                .sessionTurnGate(mock(SessionTurnGate.class))
                .build();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        HarnessAgent shared = configurer(configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                eventPublisher, new PermissionTemplateResolver()).configure(spec);

        // 行为断言：工作区写入须真正落到共享存储（BaseStore 乐观并发写入），而非本实例本地磁盘
        shared.getWorkspaceManager().getFilesystem()
                .write(RuntimeContext.empty(), "memory/MEMORY.md", "长期记忆内容");

        verify(baseStore, atLeastOnce()).putIfVersion(anyList(), anyString(), anyMap(), anyLong());
    }

    @Test
    void configure_wiresPermissionContextAndCallsResolverOnce() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-perm", "Perm Agent", "permission wiring"),
                null, null, null,
                new AgentSpec.PermissionConfig(
                        new AgentSpec.PermissionConfig.ToolGate(true, List.of("shell"), "confirm"),
                        null, null, null),
                null, new AgentSpec.SubagentsConfig(List.of()), null);
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        PermissionTemplateResolver resolver = spy(new PermissionTemplateResolver());
        HarnessAgent agent = configurer(configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                eventPublisher, resolver).configure(spec);

        assertNotNull(agent, "装配权限上下文后 Agent 须构建成功");
        verify(resolver, times(1)).resolve(eq(spec.permission()), any());
    }

    @Test
    void configure_stripsDeniedWorkspaceToolsFromToolkit() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-toolkit", "Toolkit Agent", "workspace tool strip"),
                null, null, null, null,
                new AgentSpec.ToolkitConfig(List.of("read_file", "execute", "load_skill_through_path")),
                new AgentSpec.SubagentsConfig(List.of()), null);
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        HarnessAgent agent = configurer(configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                eventPublisher, new PermissionTemplateResolver()).configure(spec);

        Set<String> toolNames = agent.getToolkit().getToolNames();
        for (String denied : List.of("read_file", "execute", "load_skill_through_path")) {
            assertFalse(toolNames.contains(denied),
                    "AgentSpec.toolkit.deniedToolNames 中的工具 " + denied + " 须在构建期被移除");
        }
    }

    @Test
    void configure_appliesGlobalDefaultDenyWhenToolkitUnDeclared() {
        // 主路径未声明 toolkit 时，全局默认 deny 集须自动并入，最小权限运行。
        AgentSpec spec = sampleSpec();
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        HarnessAgent agent = configurer(configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                eventPublisher, new PermissionTemplateResolver()).configure(spec);

        Set<String> toolNames = agent.getToolkit().getToolNames();
        for (String denied : List.of("execute", "write_file", "edit_file", "list_files", "grep_files", "glob_files")) {
            assertFalse(toolNames.contains(denied),
                    "主路径未声明 toolkit 时全局默认 deny 必须移除 " + denied);
        }
        assertTrue(toolNames.contains("read_file"),
                "全局默认 deny 须保留只读 read_file");
    }

    @Test
    void configure_rejectsHostCodeExecutionWithoutSandbox() {
        // 启动断言：显式放行 execute（omit 出 deny 列表）但本平台未绑定真实沙箱后端，启动必须失败（fail-closed）。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-code", "Code Agent", "code execution"),
                null, null, null, null,
                new AgentSpec.ToolkitConfig(List.of("write_file", "edit_file")),
                new AgentSpec.SubagentsConfig(List.of()), null);
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        assertThrows(IllegalStateException.class,
                () -> configurer(configCenter, mcpDirectory, routingPolicy,
                        modelFactory, distributedRuntimeConfig, specValidator,
                        eventPublisher, new PermissionTemplateResolver()).configure(spec),
                "omit execute 的 Agent 必须因未绑定真实沙箱后端而启动失败");
    }

    @Test
    void configure_allowsHostCodeExecutionWhenSandboxDeclared() {
        // 显式放行 execute（omit 出 deny 列表）且声明 docker 沙箱后端，启动必须成功（在容器内执行）。
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("agent-coder", "Coder Agent", "sandboxed code execution"),
                null, null, null, null,
                new AgentSpec.ToolkitConfig(List.of("write_file", "edit_file")),
                new AgentSpec.SubagentsConfig(List.of()),
                new AgentSpec.SandboxConfig("docker", null, null, null, null, null,
                        null, null, null, "SESSION", null));
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        HarnessAgent agent = configurer(configCenter, mcpDirectory, routingPolicy,
                modelFactory, distributedRuntimeConfig, specValidator,
                eventPublisher, new PermissionTemplateResolver()).configure(spec);

        assertNotNull(agent, "声明 docker 沙箱后端后 execute 应在容器内放行，Agent 构建成功");
        assertTrue(agent.getToolkit().getToolNames().contains("execute"),
                "声明的沙箱 Agent 须保留 execute 工具（在容器内执行）");
    }

    // ---- 缺口 A：技能选择（SkillConfig → SkillFilter）判定矩阵 ----
    // 默认 configurer 的 museToolRegistrars 为空（MUSE 未启用）；WHITELIST + MUSE 启用的降级分支见
    // yunxi-intelligence 模块测试（该分支需注入非空 MuseToolRegistrar，而 yunxi-runtime 不依赖 intelligence）。

    private AgentConfigurer newConfigurer() {
        return configurer(configCenter, mcpDirectory, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, eventPublisher, new PermissionTemplateResolver());
    }

    private static AgentSpec specWithSkill(AgentSpec.SkillMode mode, List<String> skills) {
        return new AgentSpec(
                new AgentSpec.Identity("agent-skill", "Skill Agent", "skill selection"),
                new AgentSpec.SkillConfig(skills, mode),
                null, null, null, null, new AgentSpec.SubagentsConfig(List.of()), null);
    }

    @Test
    void skillSelection_nullSkill_usesFrameworkDefault() {
        var plan = newConfigurer().resolveSkillSelection(sampleSpec());
        assertFalse(plan.disableDefault());
        assertNull(plan.filter(), "未声明 skill 应沿用框架默认全开（不调 skillFilter）");
    }

    @Test
    void skillSelection_all_usesFrameworkDefault() {
        var plan = newConfigurer().resolveSkillSelection(specWithSkill(AgentSpec.SkillMode.ALL, List.of("x")));
        assertFalse(plan.disableDefault());
        assertNull(plan.filter());
    }

    @Test
    void skillSelection_none_disablesDefaultWorkspace() {
        var plan = newConfigurer().resolveSkillSelection(specWithSkill(AgentSpec.SkillMode.NONE, List.of()));
        assertTrue(plan.disableDefault());
        assertNull(plan.filter());
    }

    @Test
    void skillSelection_emptyListBackwardCompatibleNone() {
        var plan = newConfigurer().resolveSkillSelection(specWithSkill(null, List.of()));
        assertTrue(plan.disableDefault(), "空列表未声明模式应视为 NONE（向后兼容）");
    }

    @Test
    void skillSelection_blacklistExcludesButKeepsOthers() {
        var plan = newConfigurer().resolveSkillSelection(
                specWithSkill(AgentSpec.SkillMode.BLACKLIST, List.of("code-reviewer")));
        assertFalse(plan.disableDefault());
        assertNotNull(plan.filter());
        assertFalse(plan.filter().isAllowed("code-reviewer"), "黑名单技能须被排除");
        assertTrue(plan.filter().isAllowed("skill-creator"), "其余技能（含 MUSE 生成）须保留");
    }

    @Test
    void skillSelection_undeclaredModeNonEmpty_defaultsToBlacklist() {
        var plan = newConfigurer().resolveSkillSelection(specWithSkill(null, List.of("code-reviewer")));
        assertFalse(plan.disableDefault());
        assertNotNull(plan.filter());
        assertFalse(plan.filter().isAllowed("code-reviewer"), "非空未声明模式默认 BLACKLIST（保飞轮）");
        assertTrue(plan.filter().isAllowed("skill-creator"));
    }

    @Test
    void skillSelection_whitelistWithoutMuse_usesOnly() {
        // 默认 configurer 的 museToolRegistrars 为空（MUSE 未启用）→ WHITELIST 直译为 only。
        var plan = newConfigurer().resolveSkillSelection(
                specWithSkill(AgentSpec.SkillMode.WHITELIST, List.of("skill-creator")));
        assertFalse(plan.disableDefault());
        assertNotNull(plan.filter());
        assertTrue(plan.filter().isAllowed("skill-creator"), "白名单技能须可见");
        assertFalse(plan.filter().isAllowed("other"), "白名单之外须屏蔽");
    }

    @Test
    void skillSelection_whitelistWithMuse_downgradesToBlacklist() throws Exception {
        AgentConfigurer configurer = newConfigurer();
        // 模拟「MUSE 已启用」：museToolRegistrars 非空。该字段为 final，经反射注入；泛型擦除使 List<Object>
        // 可写入 List<MuseToolRegistrar> 字段，运行期仅用于 isEmpty 判定，不触及元素类型。
        Field f = AgentConfigurer.class.getDeclaredField("museToolRegistrars");
        f.setAccessible(true);
        f.set(configurer, List.of(new Object()));
        var plan = configurer.resolveSkillSelection(
                specWithSkill(AgentSpec.SkillMode.WHITELIST, List.of("skill-creator")));
        assertFalse(plan.disableDefault());
        assertNotNull(plan.filter());
        assertFalse(plan.filter().isAllowed("skill-creator"),
                "MUSE 启用时 WHITELIST 降级为 BLACKLIST，技能须被排除（保留飞轮）");
        assertTrue(plan.filter().isAllowed("other"), "其余技能须保留");
    }

    // ---- 缺口 P4：零技能 Agent 摘除 MUSE 写类工具（allowMuseWriteTools 与技能边界判定同源） ----

    @Test
    void allowMuseWrite_none_disabled() {
        assertFalse(newConfigurer().allowMuseWriteTools(specWithSkill(AgentSpec.SkillMode.NONE, List.of())),
                "NONE 零技能 Agent 不可挂载写类工具（写出的技能自己加载不了）");
    }

    @Test
    void allowMuseWrite_emptyListBackwardCompatible_disabled() {
        assertFalse(newConfigurer().allowMuseWriteTools(specWithSkill(null, List.of())),
                "空列表向后兼容 NONE，不可挂载写类工具");
    }

    @Test
    void allowMuseWrite_all_enabled() {
        assertTrue(newConfigurer().allowMuseWriteTools(specWithSkill(AgentSpec.SkillMode.ALL, List.of("x"))),
                "ALL 可加载动态技能，飞轮成立，允许写工具");
    }

    @Test
    void allowMuseWrite_blacklist_enabled() {
        assertTrue(newConfigurer().allowMuseWriteTools(
                specWithSkill(AgentSpec.SkillMode.BLACKLIST, List.of("code-reviewer"))),
                "BLACKLIST 可加载动态技能，允许写工具");
    }

    // ---- builtin-export-dir 覆盖场景的工作区根对齐 ----

    private AgentConfigurer configurerWithExportDir(String dir) {
        return stubObjectProviders(new AgentConfigurer(configCenter, mcpDirectory, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, List.of(), List.of(), List.of(), eventPublisher, List.of(),
                new SessionToolGroupRegistry(), new PermissionTemplateResolver(), new MemoryProperties(), dir,
                new TenantConfig(false, "default")));
    }

    @Test
    void workspaceRoot_defaultNoAlignment() {
        var c = configurerWithExportDir(".agentscope/workspace/skills");
        assertNull(c.resolveWorkspaceRoot(), "默认配置框架 Layer 4 已物理对齐，无需重定向（零代码）");
    }

    @Test
    void workspaceRoot_overrideEndsWithSkills_redirectsToParent() {
        var c = configurerWithExportDir("/data/yunxi/skills");
        Path root = c.resolveWorkspaceRoot();
        assertNotNull(root, "覆盖到 <root>/skills 须重定向工作区根");
        assertTrue(root.isAbsolute(), "重定向后的工作区根须为绝对路径");
        assertTrue(root.endsWith(Path.of("data", "yunxi")),
                "覆盖到 <root>/skills 须重定向到其上一级目录，期望以 data/yunxi 结尾，实际：" + root);
    }

    @Test
    void workspaceRoot_overrideNotEndingWithSkills_returnsNullAndWarns() {
        var c = configurerWithExportDir("/data/yunxi-skills");
        assertNull(c.resolveWorkspaceRoot(),
                "不以 /skills 结尾无法对齐（Layer 4 固定取 ${root}/skills），仅告警不阻断");
    }

    // ---- 缺口 TaskList：todo_write 双通道（全局 yunxi.agent.taskList.enabled ∪ per-Agent plan.taskList，OR 生效） ----

    private static AgentSpec specWithPlan(boolean taskList) {
        return new AgentSpec(
                new AgentSpec.Identity("agent-plan", "Plan Agent", "task list wiring"),
                null, null, null, null, null, new AgentSpec.SubagentsConfig(List.of()), null, null,
                new AgentSpec.PlanConfig(taskList));
    }

    private static void setGlobalTaskListEnabled(AgentConfigurer configurer, boolean value) throws Exception {
        Field f = AgentConfigurer.class.getDeclaredField("taskListEnabled");
        f.setAccessible(true);
        f.set(configurer, value);
    }

    @Test
    void configure_defaultOffPlanUnset_omitsTodoWrite() {
        // 默认关 + 未声明 plan：todo_write 不得注册（零回归）。
        AgentSpec spec = sampleSpec();
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        HarnessAgent agent = newConfigurer().configure(spec);
        assertFalse(agent.getToolkit().getToolNames().contains("todo_write"),
                "默认关且未声明 plan 时不得注册 todo_write");
    }

    @Test
    void configure_globalOn_registersTodoWrite() throws Exception {
        // 全局开关开启（反射注入 @Value 字段）：todo_write 必须注册。
        AgentSpec spec = sampleSpec();
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        AgentConfigurer configurer = newConfigurer();
        setGlobalTaskListEnabled(configurer, true);
        HarnessAgent agent = configurer.configure(spec);
        assertTrue(agent.getToolkit().getToolNames().contains("todo_write"),
                "全局开关开启时必须注册 todo_write");
    }

    @Test
    void configure_perAgentOn_registersTodoWrite() {
        // 全局关 + per-Agent plan.taskList=true：OR 生效，必须注册。
        AgentSpec spec = specWithPlan(true);
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        HarnessAgent agent = newConfigurer().configure(spec);
        assertTrue(agent.getToolkit().getToolNames().contains("todo_write"),
                "per-Agent plan.taskList=true 时必须注册 todo_write");
    }

    @Test
    void configure_globalOnPerAgentOff_stillRegistersTodoWrite() throws Exception {
        // 全局开 + per-Agent plan.taskList=false：OR 语义，全局优先，仍注册。
        AgentSpec spec = specWithPlan(false);
        DistributedStore store = buildStore();
        stubCollaborators(spec, configCenter, routingPolicy, modelFactory,
                distributedRuntimeConfig, specValidator, store);

        AgentConfigurer configurer = newConfigurer();
        setGlobalTaskListEnabled(configurer, true);
        HarnessAgent agent = configurer.configure(spec);
        assertTrue(agent.getToolkit().getToolNames().contains("todo_write"),
                "全局开时即使 per-Agent 显式 false，OR 语义仍须注册 todo_write");
    }
}
