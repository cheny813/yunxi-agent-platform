package io.yunxi.runtime;

import java.io.IOException;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.yunxi.capability.McpDirectory;
import io.yunxi.capability.ConfigurableModelRoutingPolicy;
import io.yunxi.capability.ModelRoutingPolicy;
import io.yunxi.capability.RoutedModel;
import io.yunxi.config.ConfigCenter;
import io.yunxi.config.ResolvedConfig;
import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.AgentSpec.SkillMode;
import io.yunxi.definition.AgentSpecValidator;
import io.yunxi.definition.TenantStorageMeter;
import java.nio.file.Path;
import io.yunxi.runtime.middleware.SessionToolGroupRegistry;
import io.yunxi.runtime.permission.PermissionTemplateResolver;
import io.yunxi.runtime.MemoryProperties;
import io.yunxi.runtime.rag.RagToolRegistrar;
import io.yunxi.runtime.sandbox.OptionsDrivenSandboxFilesystemSpec;
import io.yunxi.runtime.tenant.TenantAwareDistributedStore;
import io.yunxi.runtime.tenant.TenantConfig;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 运行时平面 — 唯一装配点。
 *
 * <p>每个 Agent 只能在这里、且必须只在这里构建。装配顺序是固定的：
 * 新增一个横切关切只需改动本方法，而不是 N 个调用点。
 *
 * <ol>
 *   <li>校验 {@link AgentSpec}；</li>
 *   <li>经 {@link ConfigCenter} 把配置解析为 {@link ResolvedConfig}；</li>
 *   <li>经 {@link ModelRoutingPolicy} 路由模型，并经 {@link ModelFactory} 解析候选模型，再用 {@link FailoverModelProvider} 包成主备；</li>
     *   <li>从能力平面构建 {@link Toolkit}（技能 + 来自唯一 {@link McpDirectory} 的 MCP 客户端
     *       + 经 {@link MuseToolRegistrar} SPI 挂载的 MUSE 自进化元工具
     *       + 经 {@link RagToolRegistrar} SPI 挂载的 RAG 知识库检索工具）；</li>
 *   <li>构建 {@link HarnessAgent}（name / sysPrompt / model / toolkit / distributedStore）；</li>
 *   <li>应用协议无关的 {@link MiddlewareBase} 链——对所有协议统一生效的横切装配点；</li>
 *   <li>返回装配好的 {@link HarnessAgent}（注册收口到框架协议层 registry 由应用平面 {@code AgentProvisioner} 负责）。</li>
 * </ol>
 */
public class AgentConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigurer.class);

    /**
     * 主路径工具箱默认 deny 集（最小权限基线）。
     *
     * <p>这些工具来自 AgentScope-Java 默认工作区工具集（由框架在构建每个 Agent 时自动装备、非 yunxi 自建）：
     * {@code execute}（宿主代码执行）、{@code write_file}/{@code edit_file}/{@code list_files}/
     * {@code grep_files}/{@code glob_files}（文件写/列/删）。主路径在未显式声明
     * {@code toolkit.deniedToolNames} 时自动并入此集，于构建期经框架 ToolFilter 物理移除
     * （removeTool——工具既不可见也不可调用），使主路径默认不执行 LLM 生成的代码、且仅保留只读
     * {@code read_file}（受框架 WorkspacePathNormalizer 约束在 workspace 根内）。</p>
     *
     * <p>显式声明 {@code toolkit.deniedToolNames} 即 REPLACE 全局默认（不自多重并，因框架 ToolsConfig.deny
     * 不可被 allow 反悔）；代码执行 Agent 须在该列表中列全其余受限工具、唯独 omit {@code execute} 显式放行，
     * 并绑定真实沙箱后端，否则启动失败。</p>
     */
    private static final Set<String> DEFAULT_TOOLKIT_DENY = Set.of(
            "execute", "write_file", "edit_file", "list_files", "grep_files", "glob_files");

    private final ConfigCenter configCenter;
    private final McpDirectory mcpDirectory;
    private final ModelRoutingPolicy routingPolicy;
    private final ModelFactory modelFactory;
    private final DistributedRuntimeConfig distributedRuntimeConfig;
    private final AgentSpecValidator specValidator;
    private final List<MiddlewareBase> middlewares;
    private final List<MuseToolRegistrar> museToolRegistrars;
    private final List<RagToolRegistrar> ragToolRegistrars;
    private final ApplicationEventPublisher eventPublisher;
    private final List<AgentBuildContributor> buildContributors;
    private final SessionToolGroupRegistry sessionToolGroupRegistry;
    private final PermissionTemplateResolver permissionTemplateResolver;
    private final MemoryProperties memoryProperties;
    /** 内建技能落地目录（yunxi.muse.builtin-export-dir）；覆盖默认值时需对齐框架 Layer 4 技能目录（运维覆盖到非默认 <root>/skills 场景）。 */
    private final String builtinExportDir;

    /**
     * MCP 自动装载开关（{@code yunxi.mcp.auto-load-all}，默认 false）。
     * 开启时，{@link #buildToolkit} 装载 {@link McpDirectory} 中注册的全部 MCP 服务器，
     * 由 Agent 在运行时自行选择要调用的工具，各 Agent 无需在定义里逐个声明 {@code mcp.servers}；
     * 关闭时仍按每个 Agent 的 {@code mcp.servers} 精确装载（默认行为，保留范围控制）。
     */
    @Value("${yunxi.mcp.auto-load-all:false}")
    private boolean mcpAutoLoadAll;

    /**
     * 团队 / 策展只读技能仓库：经 {@code yunxi.skill.git.enabled} 接线框架原生
     * {@link GitSkillRepository}。默认关闭（无 bean），{@link ObjectProvider#getIfAvailable()} 返回 null，不接线。
     * 鉴权复用系统级 git 配置（SSH 密钥 / HTTPS token 凭据助手 / token 内联 URL）。
     */
    @Autowired
    private ObjectProvider<GitSkillRepository> gitSkillRepositoryProvider;

    /**
     * 用户 / MUSE 版本化技能后端：经 {@code yunxi.skill.jdbc.enabled} 接线框架原生
     * {@link JdbcAgentSkillRepository}（复用调度 MySQL，表结构需预建）。默认关闭（无 bean）。该 bean 为
     * {@code @Primary AgentSkillRepository}，故 MUSE 的 SkillRefiner 写回落到此处（Git 只读不参与写）。
     */
    @Autowired
    private ObjectProvider<JdbcAgentSkillRepository> jdbcSkillRepositoryProvider;

    /**
     * 租户存储计量钩子（yunxi-definition 接口，实现在 yunxi-app）。默认关闭（无 bean）时
     * {@link ObjectProvider#getIfAvailable()} 返回 null，装饰器静默跳过计量（fail-open）。
     */
    @Autowired
    private ObjectProvider<TenantStorageMeter> tenantStorageMeterProvider;

    /**
     * 跨实例事件总线（{@code yunxi.cluster.mode=cluster} 时由 RuntimeConfiguration 装配的
     * {@code RedisMessageBus} bean；standalone 无此 bean，{@link ObjectProvider#getIfAvailable()}
     * 返回 null，装配时跳过，agent 仍可布建（fail-open）。
     */
    @Autowired
    private ObjectProvider<MessageBus> messageBusProvider;

    /**
     * 任务清单（todo_write）全局开关（{@code yunxi.agent.taskList.enabled}，默认 false）。
     * 与 per-Agent 的 {@code plan.taskList} 构成双通道，二者 OR 生效；后者为 true 即覆盖本全局关。
     * 默认关契合平台最小权限姿态，对未声明 plan 的既有 Agent 零回归。
     */
    @Value("${yunxi.agent.taskList.enabled:false}")
    private boolean taskListEnabled;

    private final TenantConfig tenantConfig;

    public AgentConfigurer(ConfigCenter configCenter,
                           McpDirectory mcpDirectory,
                           ModelRoutingPolicy routingPolicy,
                           ModelFactory modelFactory,
                           DistributedRuntimeConfig distributedRuntimeConfig,
                           AgentSpecValidator specValidator,
                           List<MiddlewareBase> middlewares,
                           List<MuseToolRegistrar> museToolRegistrars,
                           List<RagToolRegistrar> ragToolRegistrars,
                           ApplicationEventPublisher eventPublisher,
                           List<AgentBuildContributor> buildContributors,
                           SessionToolGroupRegistry sessionToolGroupRegistry,
                           PermissionTemplateResolver permissionTemplateResolver,
                           MemoryProperties memoryProperties,
                           String builtinExportDir,
                           TenantConfig tenantConfig) {
        this.configCenter = Objects.requireNonNull(configCenter);
        this.mcpDirectory = Objects.requireNonNull(mcpDirectory);
        this.routingPolicy = Objects.requireNonNull(routingPolicy);
        this.modelFactory = Objects.requireNonNull(modelFactory);
        this.distributedRuntimeConfig = Objects.requireNonNull(distributedRuntimeConfig);
        this.specValidator = Objects.requireNonNull(specValidator);
        this.middlewares = middlewares != null ? middlewares : List.of();
        this.museToolRegistrars = museToolRegistrars != null ? museToolRegistrars : List.of();
        this.ragToolRegistrars = ragToolRegistrars != null ? ragToolRegistrars : List.of();
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.buildContributors = buildContributors != null ? buildContributors : List.of();
        this.sessionToolGroupRegistry = Objects.requireNonNull(sessionToolGroupRegistry);
        this.permissionTemplateResolver = Objects.requireNonNull(permissionTemplateResolver);
        this.memoryProperties = Objects.requireNonNull(memoryProperties);
        this.builtinExportDir = Objects.requireNonNull(builtinExportDir);
        this.tenantConfig = Objects.requireNonNull(tenantConfig);
    }

    /** 校验、装配并注册单个 Agent。注册进框架协议层 registry 由应用平面 {@code AgentProvisioner} 收口。 */
    private static final Set<String> SUPPORTED_SANDBOX_BACKENDS = Set.of("docker");
    private static final Set<String> SUPPORTED_ISOLATION_SCOPES =
            Set.of("SESSION", "USER", "AGENT", "GLOBAL");
    private static final String DEFAULT_SANDBOX_IMAGE = "ubuntu:22.04";

    /**
     * 把声明式 {@code AgentSpec.SandboxConfig} 翻译为框架 {@code DockerSandboxClientOptions}
     * （声明式 sandbox 配置 → 框架选项的翻译 seam）。
     *
     * <p>注意：{@code SandboxConfig.environment} 不映射到此处。框架 {@code DockerSandbox} 仅在
     * {@code docker run} 时从 {@code WorkspaceSpec.getEnvironment()} 注入 {@code -e}，而
     * {@code DockerSandboxClientOptions.environment} 不被框架消费，故环境变量统一在
     * {@link #resolveSandboxWorkspaceSpec} 映射，避免无效配置。</p>
     */
    private static DockerSandboxClientOptions resolveSandboxClientOptions(AgentSpec.SandboxConfig cfg) {
        DockerSandboxClientOptions opts = new DockerSandboxClientOptions();
        opts.setImage(cfg.image() != null ? cfg.image() : DEFAULT_SANDBOX_IMAGE);
        if (cfg.workspaceRoot() != null) {
            opts.setWorkspaceRoot(cfg.workspaceRoot());
        }
        if (cfg.memorySizeBytes() != null) {
            opts.memorySizeBytes(cfg.memorySizeBytes());
        }
        if (cfg.cpuCount() != null) {
            opts.cpuCount(cfg.cpuCount());
        }
        if (cfg.exposedPorts() != null && !cfg.exposedPorts().isEmpty()) {
            opts.exposedPorts(cfg.exposedPorts().stream().mapToInt(Integer::intValue).toArray());
        }
        if (cfg.network() != null) {
            opts.network(cfg.network());
        }
        if (cfg.additionalRunArgs() != null && !cfg.additionalRunArgs().isEmpty()) {
            opts.additionalRunArgs(cfg.additionalRunArgs().toArray(new String[0]));
        }
        return opts;
    }

    /** 把声明式 {@code AgentSpec.SandboxConfig} 翻译为框架 {@code WorkspaceSpec}（声明式 sandbox 配置 → 框架选项的翻译 seam）。 */
    private static WorkspaceSpec resolveSandboxWorkspaceSpec(AgentSpec.SandboxConfig cfg) {
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        if (cfg.workspaceRoot() != null) {
            workspaceSpec.setRoot(cfg.workspaceRoot());
        }
        if (cfg.environment() != null) {
            workspaceSpec.setEnvironment(cfg.environment());
        }
        return workspaceSpec;
    }

    /** v1 仅支持 noop 快照（不持久化）；非 noop 类型由校验器在启动前拒绝。 */
    private static SandboxSnapshotSpec resolveSandboxSnapshotSpec(AgentSpec.SandboxConfig cfg) {
        return new NoopSnapshotSpec();
    }

    /** 把声明式隔离粒度翻译为框架 {@code IsolationScope}；缺省 SESSION。 */
    private static IsolationScope resolveSandboxIsolationScope(AgentSpec.SandboxConfig cfg) {
        String value = cfg.isolationScope() != null ? cfg.isolationScope() : "SESSION";
        if (!SUPPORTED_ISOLATION_SCOPES.contains(value)) {
            throw new IllegalArgumentException("sandbox.isolationScope 非法：" + value);
        }
        return IsolationScope.valueOf(value);
    }

    public HarnessAgent configure(AgentSpec spec) {
        return configureDetailed(spec).agent();
    }

    /**
     * 构建 Agent 并返回实例与构建期不可达的 MCP 服务器清单。
     * 清单非空表示该 Agent 的部分 MCP 工具未装载（构建期静默降级），布建方据此登记待换装；
     * 目标恢复可达后由 {@code probeAndRebuild} 调度整体重建实例，工具与权限上下文同源同刻换装。
     */
    public ProvisionedAgent configureDetailed(AgentSpec spec) {
        AgentSpec.ValidationResult result = specValidator.validate(spec);
        if (!result.valid()) {
            throw new IllegalArgumentException(
                    "AgentSpec 校验未通过，agentId=" + spec.identity().agentId() + "，错误：" + result.errors());
        }

        ResolvedConfig resolved = configCenter.resolve(spec);
        Model model = resolveModel(spec);
        BuiltToolkit built = buildToolkit(spec);
        Toolkit toolkit = built.toolkit();
        sessionToolGroupRegistry.register(
                spec.identity().name(),
                toolkit.getActiveGroups());

        // 跨实例会话状态并发写入的正确性最终由底层框架的版本化状态存储 CAS 保证；会话轮次闸门
        // （sessionTurnGate 非 null）是减少冲突浪费的主保护，冲突计数指标（getStateConflictCount）用于可观测。
        // AgentScope-Java 的 HarnessAgent.Builder 已暴露 conflictPolicy 入口，且 fromAgent 会传播该策略。
        // 正确性轴选择 FAIL：冲突即抛 ConcurrentSessionModificationException
        // 跳过写入，而非静默 OVERWRITE；须与本平台已装配的分布式会话 turnGate 配套
        // （DistributedStore.sessionTurnGate 非空时框架硬性要求 FAIL）。
        ReActAgent.Builder inner = ReActAgent.builder()
                .name(spec.identity().name())
                .sysPrompt(sysPromptOf(spec))
                .model(model)
                .toolkit(toolkit)
                .permissionContext(permissionTemplateResolver.resolve(spec.permission(), built.mcpToolNames()));

        HarnessAgent.Builder builder = HarnessAgent.builder().fromAgent(inner.build())
                .conflictPolicy(ConflictPolicy.FAIL);

        // 任务清单（todo_write）双通道开关：全局 yunxi.agent.taskList.enabled ∪ per-Agent plan.taskList，
        // OR 生效。默认关（二者均 false），对未声明 plan 的既有 Agent 零回归；开启时经框架
        // HarnessAgent.Builder.enableTaskList 注册 todo_write 工具与 TaskReminderMiddleware，
        // 不引入独立存储（框架内置内存态），不修改 AgentScope-Java 框架源码。
        boolean taskListEnabledPerAgent = spec.plan() != null && spec.plan().taskList();
        builder.enableTaskList(this.taskListEnabled || taskListEnabledPerAgent);

        // 工具箱裁剪：把「平台全局默认 deny 集」或定义平面显式声明（AgentSpec.ToolkitConfig.deniedToolNames）
        // 的工具从 AgentScope-Java 默认工作区工具集（read_file / write_file / edit_file / list_files /
        // grep_files / glob_files / execute 等，由框架自动装备、非 yunxi 自建）中摘除，翻译为框架
        // ToolsConfig.deny，于构建期经 ToolFilter.apply 物理移除（removeTool）——工具既不可见也不可调用，属有意关闭。
        // 这些工具来自框架而非本平台，摘除非遗漏。
        // 默认态势：未显式声明 toolkit.deniedToolNames 的主路径 Agent 一律并入 DEFAULT_TOOLKIT_DENY，
        // 最小权限运行（不执行 LLM 生成代码、仅保留只读 read_file）；声明即 REPLACE 全局默认（不自多重并，
        // 因框架 ToolsConfig.deny 不可被 allow 反悔）。代码执行 Agent 须在该列表中列全其余受限工具、唯独 omit
        // execute 显式放行，并绑定真实沙箱后端（见下方启动断言），否则启动失败。
        Set<String> effectiveToolkitDeny = computeEffectiveToolkitDeny(spec.toolkit());
        ToolsConfig toolsConfig = new ToolsConfig();
        toolsConfig.setDeny(List.copyOf(effectiveToolkitDeny));
        builder.toolsConfig(toolsConfig);

        DistributedStore store = distributedRuntimeConfig.distributedStore();
        if (store != null && tenantConfig.enabled()) {
            store = new TenantAwareDistributedStore(store, tenantConfig, tenantStorageMeterProvider.getIfAvailable());
        }
        // 共享存储工作区：长期记忆（工作区 MEMORY.md）与 knowledge 必须落在共享存储上，
        // 否则多副本部署时每实例各写一份即分叉（双份真相）。框架默认工作区文件系统指向本实例本地磁盘，
        // 故产品平面在分布式后端可用时显式切到框架远程文件系统实现。
        RemoteFilesystemSpec sharedWorkspace = null;
        if (store != null) {
            builder.distributedStore(store);
            // AgentScope-Java 2.1.0 的 HarnessAgent.Builder 仅在 legacySessionHistory 或 sandbox 场景下
            // 才从 distributedStore 自动装配 agentStateStore（见 harness HarnessAgent.Builder.build），
            // 而 yunxi 走无沙箱、非 legacy 的常规路径，若不显式接线，getStateStore() 将为 null，
            // 会话状态会静默回退到框架默认的本地 JsonFileAgentStateStore，而非配置的分布式存储。
            // 因此产品平面在此显式把分布式状态存储接入，确保状态持久化落到配置的存储后端。
            AgentStateStore agentStateStore = store.agentStateStore();
            if (agentStateStore != null) {
                builder.stateStore(agentStateStore);
                // DistributedStore.Builder 在 build 期强制 baseStore 非空，故此处无需判空。
                sharedWorkspace = new RemoteFilesystemSpec(store.baseStore());
            }
        }

        // 跨实例事件总线：cluster 模式经 RedisMessageBus 提供 Redis pub/sub 真·推送；standalone 或总线
        // bean 缺失时跳过，框架回退到本地 WorkspaceMessageBus，主流程不阻断（fail-open）。事件契约由后续变更定义。
        MessageBus messageBus = messageBusProvider != null ? messageBusProvider.getIfAvailable() : null;
        if (messageBus != null) {
            builder.messageBus(messageBus);
        }

        // 唯一的横切装配点：协议无关的中间件，对所有协议统一生效。
        for (MiddlewareBase middleware : middlewares) {
            builder.middleware(middleware);
        }
        // 构建期贡献点：产品平面在 build 前注入横切中间件（例如 aistio 数据面事件流观测中间件），保持运行时对集成零感知。
        for (AgentBuildContributor contributor : buildContributors) {
            MiddlewareBase contributed = contributor.buildTimeMiddleware(spec);
            if (contributed != null) {
                builder.middleware(contributed);
            }
        }

        // 记忆与压缩策略：yunxi 显式拥有，按 MemoryProperties 翻译为框架配置，不静默沿用框架默认。
        builder.compaction(toCompactionConfig(memoryProperties))
                .memory(toMemoryConfig(memoryProperties));

        // 子 Agent 接线：定义平面声明翻译为框架 SubagentDeclaration，由 HarnessAgent 构建期自动挂上能力。
        configureSubagents(spec, builder);

        // 技能边界：把定义平面 SkillConfig 翻译为框架 SkillFilter（场景专精且不杀自进化飞轮，白名单禁用于自进化 Agent 以免屏蔽动态技能）。
        SkillSelectionPlan skillPlan = resolveSkillSelection(spec);
        if (skillPlan.disableDefault()) {
            builder.disableDefaultWorkspaceSkills();
        } else if (skillPlan.filter() != null) {
            builder.skillFilter(skillPlan.filter());
        }

        // 工作区技能目录对齐：运维覆盖 builtin-export-dir 到非默认 <root>/skills 时，重定向框架默认
        // 工作区根，使 Layer 4（WorkspaceSkillRepository，加载 ${root}/skills）与内建/MUSE 写目录对齐；默认
        // 配置（.agentscope/workspace/skills）框架已物理对齐，无需任何操作（零代码，无双加载）。
        Path wsRoot = resolveWorkspaceRoot();
        if (wsRoot != null) {
            builder.workspace(wsRoot);
        }
        // 内置技能全局零复制接线：经 ClasspathSkillRepository 从 jar 的 builtin-skills/ 资源零复制加载，
        // 落在框架 composeSkillRepositories 的 Layer 2。框架 HarnessSkillMiddleware.mergeRepositories
        // 按列表顺序后者（同名校验）胜出，即 L4 用户 > L3 workspace/MUSE > L2 内置 > L1 全局，
        // 故内置作底座、MUSE 演进天然覆盖内置，无需重指 MUSE 写路径（MUSE 写同时落磁盘 Layer3 与
        // 后续 JDBC 版本化后端）。这取代 BuiltinSkillLoader 把 SKILL.md 落地到 workspace 的
        // 「双份真相」workaround；
        // builtin-skills/{name}/tests/ 仍由 BuiltinSkillLoader 物化到磁盘供 MUSE 评估器执行。
        try {
            builder.skillRepository(new ClasspathSkillRepository("builtin-skills"));
        } catch (IOException e) {
            log.warn("[agent] 内置技能 ClasspathSkillRepository 初始化失败，跳过零复制接线: {}", e.getMessage());
        }
        // 团队 / 策展只读技能仓库：从团队 Git 仓库零复制加载，落在 Layer 2。
        // 鉴权复用系统级 git 配置（SSH 密钥 / HTTPS token 凭据助手 / token 内联 URL），默认关闭零回归。
        GitSkillRepository gitRepo = gitSkillRepositoryProvider.getIfAvailable();
        if (gitRepo != null) {
            builder.skillRepository(gitRepo);
        }
        // 用户 / MUSE 版本化技能后端：复用调度 MySQL 的可写 JDBC 仓库，落在 Layer 2
        // 末位；依 mergeRepositories「同名校验后者胜出」覆盖内置 / 团队仓库（L4 用户 > L3 workspace/MUSE >
        // L2 内置/团队/JDBC 末位优先）。该 bean 为 @Primary AgentSkillRepository，MUSE 的 SkillRefiner 写回落到此处。
        // 默认关闭，须先以 deploy/infra/init/skill-store.sql 预建表结构。
        JdbcAgentSkillRepository jdbcRepo = jdbcSkillRepositoryProvider.getIfAvailable();
        if (jdbcRepo != null) {
            builder.skillRepository(jdbcRepo);
        }

        // 真实沙箱后端绑定：仅当 AgentSpec.sandbox 显式声明时才经 builder.filesystem 装配框架
        // DockerSandboxClient，主路径 execute 才被放行（见下方启动断言放松）。不声明则走默认 deny，
        // 不在宿主文件系统上跑不可信 LLM 生成代码。这是 yunxi 产品平面的安全策略决策，框架执行器零自研。
        if (spec.sandbox() != null) {
            AgentSpec.SandboxConfig sb = spec.sandbox();
            if (!SUPPORTED_SANDBOX_BACKENDS.contains(sb.backend())) {
                throw new IllegalStateException(
                        "Agent " + spec.identity().agentId()
                                + " 声明的沙箱后端 " + sb.backend() + " 不受支持；当前仅支持 docker。");
            }
            DockerSandboxClientOptions clientOptions = resolveSandboxClientOptions(sb);
            WorkspaceSpec workspaceSpec = resolveSandboxWorkspaceSpec(sb);
            SandboxSnapshotSpec snapshotSpec = resolveSandboxSnapshotSpec(sb);
            IsolationScope isolationScope = resolveSandboxIsolationScope(sb);
            SandboxExecutionGuard executionGuard = distributedRuntimeConfig.sandboxExecutionGuard();
            builder.filesystem(new OptionsDrivenSandboxFilesystemSpec(
                    clientOptions, workspaceSpec, snapshotSpec, isolationScope, executionGuard));
        } else if (sharedWorkspace != null) {
            // 沙箱工作区由沙箱后端自身承载（文件系统随沙箱生命周期），非沙箱路径才落共享存储。
            builder.filesystem(sharedWorkspace);
        }

        HarnessAgent agent = builder.build();

        // 启动断言：宿主代码执行（execute 不在最终 deny 集）必须绑定真实沙箱后端，禁止在宿主 filesystem
        // 上跑不可信的 LLM 生成代码。本平台默认 deny execute；仅当声明真实沙箱后端（spec.sandbox() != null）
        // 且经上方 builder.filesystem 绑定框架 DockerSandboxClient 时，才允许 execute 在容器内执行。这与
        // 「主路径不沙箱化、默认 deny execute」的安全基线一致。
        if (!effectiveToolkitDeny.contains("execute") && spec.sandbox() == null) {
            throw new IllegalStateException(
                    "Agent " + spec.identity().agentId()
                    + " 启用了宿主代码执行（execute 未出现在工具箱 deny 列表），但当前未绑定真实沙箱后端；"
                    + "禁止在宿主文件系统上运行不可信的 LLM 生成代码。"
                    + "如需放行 execute，请先声明 AgentSpec.sandbox（docker 后端）并显式将 execute 从 deny 列表移除，"
                    + "否则应保持 execute 默认被拒绝。");
        }
        // 构建后审计：打印本 Agent 最终装备的工具名（终态），用于验证 toolkit.deniedToolNames
        // （AgentSpec.ToolkitConfig）是否真正从 HarnessAgent 工具箱剥离了声明工具。注意 Toolkit 构造期
        // 的「Registered tool」日志发生在 ToolsConfig.deny 应用之前，不能直接证明 deny 生效，故以构建后
        // getToolSchemas() 终态为准。
        if (log.isInfoEnabled()) {
            List<String> equipped = agent.getToolkit().getToolSchemas().stream()
                    .map(ToolSchema::getName)
                    .sorted()
                    .toList();
            log.info("[工具箱审计] agent={} 已装备工具数={} 工具列表={}",
                    spec.identity().agentId(), equipped.size(), equipped);
        }
        eventPublisher.publishEvent(new AgentBuiltEvent(this, agent));
        return new ProvisionedAgent(agent, built.failedMcpServers());
    }

    /** Agent 实例与本轮构建期不可达的 MCP 服务器清单（实例换装的登记与恢复依据）。 */
    public record ProvisionedAgent(HarnessAgent agent, List<String> failedMcpServers) {}

    /**
     * 把 {@link MemoryProperties} 的压缩策略翻译为 AgentScope-Java 的 {@link CompactionConfig}。
     *
     * <p>此处只桥接 yunxi 显式拥有的阈值（触发轮次、保留 token 区间、预留预算、保留消息数、压缩前落盘），
     * 其余未暴露的字段（summaryPrompt / truncateArgs / prune / strategy / model）沿用框架默认，
     * 后续若需平台级统一策略再在此扩展。</p>
     *
     * @param props 平台记忆策略配置
     * @return 框架压缩配置
     */
    private static CompactionConfig toCompactionConfig(MemoryProperties props) {
        MemoryProperties.Compaction c = props.compaction();
        return CompactionConfig.builder()
                .triggerMessages(c.getTriggerMessages())
                .keepTokensMin(c.getKeepTokensMin())
                .keepTokensMax(c.getKeepTokensMax())
                .keepTokensRatio(c.getKeepTokensRatio())
                .reserved(c.getReserved())
                .keepMessages(c.getKeepMessages())
                .flushBeforeCompact(c.isFlushBeforeCompact())
                .build();
    }

    /**
     * 把 {@link MemoryProperties} 的留存策略翻译为 AgentScope-Java 的 {@link MemoryConfig}。
     *
     * @param props 平台记忆策略配置
     * @return 框架记忆配置
     */
    private static MemoryConfig toMemoryConfig(MemoryProperties props) {
        MemoryProperties.Retention r = props.retention();
        return MemoryConfig.builder()
                .dailyFileRetentionDays(r.getDailyFileRetentionDays())
                .consolidationMinGap(Duration.ofMinutes(r.getConsolidationMinGapMinutes()))
                .consolidationMaxTokens(r.getConsolidationMaxTokens())
                .flushTrigger(MemoryProperties.toFrameworkFlushTrigger(r.getFlushTrigger(), r.getFlushThrottleMinutes()))
                .build();
    }

    /**
     * 把定义平面的子 Agent 声明翻译为 AgentScope-Java 的 {@link SubagentDeclaration}，并挂到构建器。
     *
     * <p>通用 sub-agent 由 yunxi 采纳并接线：定义平面只声明子 Agent 的身份 / 职责 / 工具边界（纯数据），
     * 此处翻译为框架声明并交给 {@code HarnessAgent.Builder.subagents(...)}，由框架在构建期自动挂上对应的
     * sub-agent 中间件与后台执行能力。模型缺省时由框架继承父 Agent，工具 / 技能缺省沿用父 Agent 边界，
     * 不在此重复任何框架语义，不与框架能力形成并行的事实源。</p>
     *
     * @param spec 平台 Agent 定义
     * @param builder HarnessAgent 构建器
     */
    private static void configureSubagents(AgentSpec spec, HarnessAgent.Builder builder) {
        AgentSpec.SubagentsConfig cfg = spec.subagents();
        if (cfg == null || cfg.subagents() == null || cfg.subagents().isEmpty()) {
            return;
        }
        List<SubagentDeclaration> declarations = cfg.subagents().stream()
                .map(AgentConfigurer::toSubagentDeclaration)
                .toList();
        builder.subagents(declarations);
    }

    /**
     * 把单个 {@link io.yunxi.definition.AgentSpec.SubagentSpec} 翻译为框架 {@link SubagentDeclaration}。
     *
     * <p>仅搬运定义平面声明的数据（身份、职责提示、可选模型与工具 / 技能边界、执行模式）；
     * 未声明的可选项交回框架默认值，yunxi 不重新定义执行细节。</p>
     *
     * @param source 定义平面的子 Agent 声明
     * @return 框架子 Agent 声明
     */
    private static SubagentDeclaration toSubagentDeclaration(AgentSpec.SubagentSpec source) {
        SubagentDeclaration.Builder declaration = SubagentDeclaration.builder()
                .name(source.name())
                .description(source.description())
                .inlineAgentsBody(source.prompt());
        if (source.model() != null) {
            declaration.model(source.model());
        }
        if (source.tools() != null) {
            declaration.tools(source.tools());
        }
        if (source.skills() != null) {
            declaration.skills(source.skills());
        }
        if (source.maxIters() != null) {
            declaration.maxIters(source.maxIters());
        }
        if (source.steps() != null) {
            declaration.steps(source.steps());
        }
        // 执行模式缺省为 SUBAGENT（框架默认子 Agent 行为）；声明了则按声明映射。
        SubagentDeclaration.Mode mode = source.mode() == null
                ? SubagentDeclaration.Mode.SUBAGENT
                : SubagentDeclaration.Mode.valueOf(source.mode().toUpperCase());
        declaration.mode(mode);
        return declaration.build();
    }

    /** 解析 Agent 模型：auto 路由注入按任务信号动态选型的 AutoModelProvider，否则解析主模型并以兜底层叠 FailoverModelProvider。 */
    private Model resolveModel(AgentSpec spec) {
        RoutedModel routed = routingPolicy.route(spec.identity().agentId(), null, io.agentscope.core.agent.RuntimeContext.empty());
        if (routed.auto() && routingPolicy instanceof ConfigurableModelRoutingPolicy configurable) {
            // Auto 选型：注入按任务信号动态选模型的 AutoModelProvider（请求期委托，不固定模型）。
            return new AutoModelProvider(spec.identity().agentId(), configurable, modelFactory);
        }
        Model primary = modelFactory.create(routed.primaryModelId());
        if (primary == null) {
            throw new IllegalStateException("无法解析主模型，modelId=" + routed.primaryModelId());
        }
        List<Model> fallbacks = new ArrayList<>();
        if (routed.fallbackModelIds() != null) {
            for (String id : routed.fallbackModelIds()) {
                Model fallback = modelFactory.create(id);
                if (fallback != null) {
                    fallbacks.add(fallback);
                }
            }
        }
        return fallbacks.isEmpty() ? primary : FailoverModelProvider.of(primary, fallbacks);
    }

    /**
     * 构建工具箱时收集到的真实装载工具名、MCP 工具名与构建期不可达的 MCP 服务器清单。
     * {@code mcpToolNames} 用于把「MCP 订阅（mcp.servers / 自动装载）」自动派生为权限放行，
     * 使 Agent 定义只需声明订阅、无需在 allowedTools 里重复列 MCP 工具（装载与放行统一为单一来源）。
     * {@code failedMcpServers} 供实例换装：目标恢复可达后整体重建，避免重建整个应用。
     */
    private record BuiltToolkit(Toolkit toolkit, List<String> mcpToolNames, List<String> failedMcpServers) {}

    private BuiltToolkit buildToolkit(AgentSpec spec) {
        Toolkit toolkit = new Toolkit();
        // 自动装载模式：取目录中全部 MCP 服务器；否则按本 Agent 声明的 mcp.servers 精确装载。
        List<String> serverNames = mcpAutoLoadAll
                ? mcpDirectory.all().stream().map(s -> s.name()).toList()
                : (spec.mcp() != null && spec.mcp().servers() != null ? spec.mcp().servers() : List.of());
        Set<String> mcpToolNames = new LinkedHashSet<>();
        List<String> failedMcpServers = new ArrayList<>();
        for (String serverName : serverNames) {
            var serverSpec = mcpDirectory.get(serverName);
            if (serverSpec.isEmpty()) {
                // 服务未注册进目录（或 enabled:false 未装载）：静默 continue 会让该 Agent 工具集
                // 整组缺失且无从排查，必须 WARN 暴露。不记入 failedMcpServers——配置错误重试无意义。
                log.warn("MCP 服务 '{}' 未注册或未启用（enabled:false），跳过装载，该 Agent 将缺少其全部工具。"
                        + "请检查 agent-definitions 的 mcp.servers 引用与 config/mcp.yml 注册。", serverName);
                continue;
            }
            var wrapper = mcpDirectory.buildClient(serverSpec.get());
            // 框架要求工具组先于 MCP 注册存在（registerMcpClient 会校验），否则抛
            // "Tool group does not exist"。每个 Agent 构建都是新的 Toolkit，组必不存在，故此处创建。
            // createToolGroup(name, desc, active) 的默认作用域即 META（由 Agent 自身用框架内置的
            // reset_equipped_tools 元工具按需激活），无需我们手写激活/决策逻辑。
            // - 自动装载模式：注册为默认未激活的 META 组。上百个服务器只把「组级摘要」推给 LLM，
            //   每会话由主 LLM 用 reset_equipped_tools 选出相关组激活，避免把全部工具 schema 一次性灌入上下文。
            // - 显式声明模式：声明即装备，组默认激活，订阅对该 Agent 直接可见、可被 LLM 调用。
            boolean groupActive = !mcpAutoLoadAll;
            toolkit.createToolGroup(serverName, serverName, groupActive);
            // apply() 内部 .block() 同步完成注册；注册前后取工具名差集，即得本服务器装载的工具。
            // 单个 MCP 服务器在 Agent 构建期不可达时 fail-soft：仅 WARN 跳过该服务器工具，
            // 不拖垮整个 Agent 布建；不可达服务器记入 failedMcpServers，供实例换装恢复。
            Set<String> before = new HashSet<>(toolkit.getToolNames());
            try {
                toolkit.registration().mcpClient(wrapper).group(serverName).apply();
                Set<String> after = new HashSet<>(toolkit.getToolNames());
                after.removeAll(before);
                if (after.isEmpty()) {
                    // 静默降级判定：ReconnectingMcpClientWrapper 对连接失败降级为空列表，
                    // apply() 不抛异常而是注册零工具。此时以探活区分「服务器真的未暴露工具」
                    // 与「构建期不可达」：不可达者登记为失败，供布建方登记待换装。
                    if (!mcpDirectory.probe(serverName)) {
                        failedMcpServers.add(serverName);
                        if (log.isWarnEnabled()) {
                            log.warn("MCP[{}] 构建期不可达，本 Agent 装载 0 工具（已登记待换装）", serverName);
                        }
                    } else if (log.isInfoEnabled()) {
                        log.info("MCP[{}] 可达但未暴露任何工具", serverName);
                    }
                } else {
                    mcpToolNames.addAll(after);
                }
            } catch (Exception e) {
                failedMcpServers.add(serverName);
                if (log.isWarnEnabled()) {
                    log.warn("MCP[{}] 注册失败，跳过该服务器工具（已登记待换装）：{}",
                            serverName, e.getMessage());
                }
            }
        }
        // MUSE 自进化元工具（muse 工具组）。仅当 yunxi.muse.enabled=true 时存在实现，
        // 未启用则列表为空，对未启用 MUSE 的 Agent 零侵入。写类工具（evolve/prune）仅在 Agent 能加载自己
        // 写出的技能时才挂载（与技能边界判定同源），零技能 Agent 仅保留只读评估工具 eval。
        boolean allowMuseWrite = allowMuseWriteTools(spec);
        for (MuseToolRegistrar registrar : museToolRegistrars) {
            registrar.registerTools(toolkit, allowMuseWrite);
        }
        // RAG 知识库检索工具（rag 工具组）。仅当 yunxi.rag.enabled=true 时存在实现，
        // 未启用则列表为空，对未启用 RAG 的 Agent 零侵入。检索经自研 @Tool 注入 toolkit，
        // 不实现框架已 deprecated 的 Knowledge 接口，检索直接基于 DocumentChunkStore。
        for (RagToolRegistrar registrar : ragToolRegistrars) {
            registrar.registerTools(toolkit);
        }
        return new BuiltToolkit(toolkit, List.copyOf(mcpToolNames), List.copyOf(failedMcpServers));
    }

    /**
     * 计算本 Agent 工具箱的最终 deny 集合（构建期经 AgentScope-Java ToolFilter 物理移除）。
     *
     * <p>平台默认 deny 集（{@link #DEFAULT_TOOLKIT_DENY}，最小权限：不执行 LLM 生成代码、仅保留只读
     * read_file）对所有未显式声明 {@code toolkit.deniedToolNames} 的 Agent 生效，无需在定义平面声明即
     * 默认关闭宿主代码执行与文件写/列/删。定义平面显式声明 {@code deniedToolNames} 即 REPLACE 全局默认
     * （不自多重并，因框架 ToolsConfig.deny 不可被 allow 反悔）：代码执行 Agent 在其列表中列全其余受限
     * 工具、唯独 omit execute 即显式放行宿主执行，但须同时绑定真实沙箱后端（见下方启动断言）。</p>
     *
     * @param toolkitConfig 定义平面工具箱配置，可空
     * @return 本 Agent 的最终 deny 工具名集合（不可变）
     */
    private static Set<String> computeEffectiveToolkitDeny(AgentSpec.ToolkitConfig toolkitConfig) {
        if (toolkitConfig != null
                && toolkitConfig.deniedToolNames() != null
                && !toolkitConfig.deniedToolNames().isEmpty()) {
            return Set.copyOf(toolkitConfig.deniedToolNames());
        }
        return DEFAULT_TOOLKIT_DENY;
    }

    /**
     * 技能选择判定结果（纯数据，便于单测精确断言）。
     * filter 非 null 表示应调用 builder.skillFilter(filter)；disableDefault 表示应调用
     * builder.disableDefaultWorkspaceSkills()；两者皆否表示不调（沿用框架默认全开）。
     */
    record SkillSelectionPlan(SkillFilter filter, boolean disableDefault) {}

    /**
     * 把定义平面的技能边界（AgentSpec.SkillConfig）翻译为底层框架 SkillFilter 的判定结果。
     *
     * <p>该方法为纯判定（仅读取定义与全局 MUSE 开关，不触碰构建器、不抛异常），便于单测精确断言。完整裁决与
     * 动机：核心纪律是「场景专精必须用排除式（BLACKLIST），白名单
     * （WHITELIST）禁用于启用 MUSE 的自进化 Agent」，否则白名单会屏蔽 MUSE 动态生成的技能、静默杀死飞轮。</p>
     *
     * <p>判定矩阵（skillMode 缺省 null 时按 skills 是否为空兜底）：</p>
     * <ul>
     *   <li>skill == null                  → 不调（框架默认 all，零回归）</li>
     *   <li>mode == null && skills 空/null → disableDefault（空列表 = NONE，向后兼容）</li>
     *   <li>mode == null && skills 非空    → except（默认 BLACKLIST，保飞轮；缺省兜底，因「杀飞轮」不可逆、优先级高于「多暴露噪音」）</li>
     *   <li>mode == ALL                    → 不调</li>
     *   <li>mode == NONE                   → disableDefault</li>
     *   <li>mode == WHITELIST && MUSE 启用 → except + 告警（降级保飞轮；白名单会屏蔽 MUSE 动态命名技能、静默杀飞轮，故强制降级）</li>
     *   <li>mode == WHITELIST && MUSE 未启用 → only</li>
     *   <li>mode == BLACKLIST              → except</li>
     * </ul>
     */
    SkillSelectionPlan resolveSkillSelection(AgentSpec spec) {
        AgentSpec.SkillConfig sc = spec.skill();
        if (sc == null) {
            return new SkillSelectionPlan(null, false); // 未声明技能边界：沿用框架默认全开（零回归）
        }
        SkillMode mode = sc.skillMode();
        List<String> names = sc.skills() == null ? List.of() : sc.skills();
        boolean museEnabled = !museToolRegistrars.isEmpty();
        if (mode == null) {
            if (names.isEmpty()) {
                return new SkillSelectionPlan(null, true); // 空列表 = NONE（向后兼容）
            }
            // 非空但未声明模式：安全兜底为 BLACKLIST（排除式），不可逆的「杀飞轮」优先于可逆的「多暴露噪音」。
            return new SkillSelectionPlan(SkillFilter.except(toSkillNameArray(names)), false);
        }
        return switch (mode) {
            case ALL -> new SkillSelectionPlan(null, false);
            case NONE -> new SkillSelectionPlan(null, true);
            case WHITELIST -> {
                if (museEnabled) {
                    log.warn("Agent {} 配置了 WHITELIST 技能模式但同时启用了 MUSE：白名单会屏蔽其动态生成的技能、"
                            + "静默杀死自进化飞轮。已降级为 BLACKLIST（排除式）以保留飞轮，请显式改用 BLACKLIST。",
                            spec.identity().agentId());
                    yield new SkillSelectionPlan(SkillFilter.except(toSkillNameArray(names)), false);
                }
                yield new SkillSelectionPlan(SkillFilter.only(toSkillNameArray(names)), false);
            }
            case BLACKLIST -> new SkillSelectionPlan(SkillFilter.except(toSkillNameArray(names)), false);
        };
    }

    private static String[] toSkillNameArray(List<String> names) {
        return names.toArray(new String[0]);
    }

    /**
     * 判定本 Agent 是否允许挂载 MUSE 写类元工具（evolve / prune）。
     *
     * <p>「写技能」的前提是「该 Agent 能加载自己写出的技能」，否则写出来自己用不了、纯属噪音。与技能边界
     * 同源判定：仅 {@code disableDefault}（NONE / 空列表向后兼容）场景禁止写工具，其余（ALL / BLACKLIST /
     * 白名单降级 BLACKLIST）均可加载动态技能，飞轮成立。</p>
     */
    boolean allowMuseWriteTools(AgentSpec spec) {
        return !resolveSkillSelection(spec).disableDefault();
    }

    /**
     * 内建技能落地目录默认值（与 EvolutionConfig.builtinExportDir 保持一致）。配置为该值时框架 Layer 4
     * （WorkspaceSkillRepository，加载 ${workspaceRoot}/skills）已物理对齐，无需任何对齐操作。
     */
    private static final String DEFAULT_BUILTIN_EXPORT_DIR = ".agentscope/workspace/skills";

    /**
     * 解析「框架默认工作区根」以对齐 builtin-export-dir（运维覆盖到非默认 <root>/skills 场景）。
     *
     * <p>默认配置（.agentscope/workspace/skills）与框架 Layer 4 物理已对齐，返回 null（零代码）。仅当运维覆盖到
     * 非默认目录 D 时才需对齐：框架 {@code HarnessAgent.Builder.workspace(Path)}（A-4 已证实）可重定向工作区根，
     * 但若 D 不以 /skills 结尾则无法对齐（Layer 4 固定取 ${root}/skills），此时仅告警、不阻断，运维按约定改回
     * '&lt;目录&gt;/skills' 形式即可。返回需设置的 workspace 根（绝对路径），无需重定向时返回 null。</p>
     */
    Path resolveWorkspaceRoot() {
        if (DEFAULT_BUILTIN_EXPORT_DIR.equals(builtinExportDir)) {
            return null; // 默认配置：框架 Layer 4 已对齐，零代码、无双加载
        }
        Path exportPath = Path.of(builtinExportDir);
        if (exportPath.getFileName() != null && "skills".equals(exportPath.getFileName().toString())) {
            return exportPath.getParent().toAbsolutePath();
        }
        log.warn("builtin-export-dir '{}' 不以 /skills 结尾，无法通过工作区根重定向对齐框架技能目录；"
                + "请将其配置为 '<目录>/skills' 形式以使内建/MUSE 技能被框架自动加载。", builtinExportDir);
        return null;
    }

    /** 取系统提示词：优先用定义描述，缺省回退为通用占位句。 */
    private String sysPromptOf(AgentSpec spec) {
        if (spec.identity().description() != null && !spec.identity().description().isBlank()) {
            return spec.identity().description();
        }
        return "You are " + spec.identity().name() + ".";
    }
}
