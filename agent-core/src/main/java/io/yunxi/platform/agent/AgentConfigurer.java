package io.yunxi.platform.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.PlanModeMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import io.agentscope.core.permission.PermissionMode;
import io.yunxi.platform.agent.mcp.ReconnectingMcpClientWrapper;
import io.yunxi.platform.agent.middleware.ContentFilterMiddleware;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.agent.spi.MuseToolRegistrar;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.config.PermissionConfig;
import io.yunxi.platform.security.hitl.HumanToolRegistrar;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.ProfileDefinition;
import io.yunxi.platform.shared.config.ExpertConfig;
import io.yunxi.platform.shared.config.ExtensionConfig;
import io.yunxi.platform.shared.config.StageConfig;
import io.yunxi.platform.shared.config.ToolsGroupConfig;
import io.yunxi.platform.shared.config.HITLConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import io.yunxi.platform.agent.mcp.McpConfigStore;

import io.agentscope.core.tracing.OtelTracingMiddleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.net.InetAddress;
import java.util.function.Predicate;

/**
 * Agent 配置器，负责根据 YAML 定义创建并注册 Agent。
 *
 * <p>
 * 读取 agent-definitions/ 下的 YAML 配置，创建对应的 Agent（包括 Supervisor Agent）。
 * 实现 {@link SmartLifecycle}（phase=5），确保在 Spring 容器启动后自动初始化。
 * </p>
 *
 * <p>
 * 本配置类基于 AgentScope 框架构建：
 * </p>
 * <ul>
 * <li>采用 {@link io.agentscope.core.middleware.MiddlewareBase} 实现扩展机制</li>
 * <li>MCP 通过框架 McpClientBuilder（SSE/STDIO/HTTP）注册进 Toolkit</li>
 * <li>计划能力由 PlanHintMiddleware 提供</li>
 * </ul>
 */
@Component
public class AgentConfigurer implements SmartLifecycle {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(AgentConfigurer.class);

    /**
     * 构建期捕获的“意图激活工具组”快照：Agent 名称 -> 应在每次会话激活的工具组集合
     * （systemToolsGroup + mcpServersToolsGroup + 动态注册组）。
     * 供 {@link #activateSessionToolGroups} 使用，以覆盖持久化/遗留空激活组导致的
     * MCP 工具 "Unauthorized ... is not available" 问题。
     */
    private static final Map<String, List<String>> AGENT_ACTIVE_GROUPS = new ConcurrentHashMap<>();

    /**
     * 运行时动态注册的 MCP 工具组名集合（构建期快照之外的增量）。
     * 会话级激活时并入快照，避免动态注册的服务器被反激活。
     */
    private static final Set<String> DYNAMIC_ACTIVE_GROUPS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 生命周期运行标志，volatile 确保多线程可见性 */
    private volatile boolean running = false;

    /** Agent 定义加载器，从 YAML 文件加载 Agent 配置 */
    private final AgentDefinitionLoader definitionLoader;

    /** Agent 服务，用于注册和管理 Agent 实例 */
    private final AgentService agentService;

    /** 核心配置属性，包含工作空间路径、模型名称、Compaction 配置等 */
    private final AgentscopeCoreProperties coreProperties;

    /** Agent 自定义扩展 SPI 提供者（可选），允许外部对 Agent 实例进行二次定制 */
    private final ObjectProvider<AgentCustomizer> customizerProvider;

    /** 模型工厂，根据配置创建 LLM 模型实例 */
    private final ModelFactory modelFactory;

    /** 权限配置构造器，将 HITL 配置映射为框架 PermissionContextState */
    private final PermissionConfig permissionConfig;

    /** MUSE 自进化元工具注册器（可选）：仅当 yunxi.muse.enabled=true 时作为 Bean 存在 */
    private final ObjectProvider<MuseToolRegistrar> museToolRegistrarProvider;

    /** GA 原生 OpenTelemetry 链路追踪 Middleware 提供者（可选） */
    private final ObjectProvider<OtelTracingMiddleware> otelTracingMiddlewareProvider;

    /** Agent 分布式后端（可选），提供 AgentStateStore + BaseStore + SandboxSnapshot 一站式配置 */
    private DistributedStore distributedBackend;

    /** 已建立的 MCP 客户端连接缓存，按服务器名复用，避免每个 Agent 重复建连 */
    private final Map<String, McpClientWrapper> mcpClientCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 已构建 Agent 的 Toolkit 引用集合，运行时动态注入 MCP 工具组时遍历 */
    private final Set<Toolkit> registeredToolkits =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Toolkit, Boolean>());

    /** MCP 协调底座（Nacos）封装，承担目录/广播/监听协调职责 */
    private final McpConfigStore mcpConfigStore;

    /** 跨实例 reconcile 的熔断保护（复用 resilience4j circuitbreaker 防止目标不可达时雪崩） */
    private final CircuitBreaker mcpReconcileCircuitBreaker =
            CircuitBreakerRegistry.ofDefaults().circuitBreaker("mcp-dynamic-reconcile");

    /** 应用端口，用于 Nacos 协调实例注册 */
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * 构造 Agent 配置器，通过 Spring 依赖注入获取所有必要组件。
     *
     * @param definitionLoader            Agent 定义加载器
     * @param agentService                Agent 服务
     * @param coreProperties              核心配置属性
     * @param customizerProvider          Agent 自定义扩展提供者（可选）
     * @param modelFactory                模型工厂
     * @param permissionConfig             权限配置构造器，将 HITL 配置映射为框架 PermissionContextState
     * @param otelTracingMiddlewareProvider OpenTelemetry 链路追踪 Middleware 提供者（可选，GA 原生）
     */
    public AgentConfigurer(AgentDefinitionLoader definitionLoader,
            AgentService agentService,
            AgentscopeCoreProperties coreProperties,
            ObjectProvider<AgentCustomizer> customizerProvider,
            ModelFactory modelFactory,
            PermissionConfig permissionConfig,
            ObjectProvider<OtelTracingMiddleware> otelTracingMiddlewareProvider,
            ObjectProvider<MuseToolRegistrar> museToolRegistrarProvider,
            McpConfigStore mcpConfigStore) {
        this.definitionLoader = definitionLoader;
        this.agentService = agentService;
        this.coreProperties = coreProperties;
        this.customizerProvider = customizerProvider;
        this.modelFactory = modelFactory;
        this.permissionConfig = permissionConfig;
        this.otelTracingMiddlewareProvider = otelTracingMiddlewareProvider;
        this.museToolRegistrarProvider = museToolRegistrarProvider;
        this.mcpConfigStore = mcpConfigStore;
    }

    /**
     * 设置分布式后端实例。
     *
     * <p>
     * 使用 {@link DistributedStore} 统一接口配置，
     * 一次性配置 AgentStateStore + BaseStore + SandboxSnapshotSpec。
     * </p>
     *
     * @param backends DistributedStore 实例（可选，多个时取第一个非空值）
     */
    public void setDistributedBackend(DistributedStore... backends) {
        for (DistributedStore ds : backends) {
            if (ds != null) {
                this.distributedBackend = ds;
                return;
            }
        }
    }

    /**
     * 启动 Agent 初始化流程。
     *
     * <p>
     * 按顺序执行：
     * 1. 加载所有 Agent 定义
     * 2. 初始化所有 Agent 的工作空间
     * 3. 先创建非编排型 Agent（独立 Agent）
     * 4. 再创建编排型 Agent（Supervisor/Pipeline/Routing），因为编排型依赖独立 Agent
     * </p>
     */
    @Override
    public void start() {
        if (running)
            return;
        log.info("开始初始化 Agent...");
        // 加载所有 YAML Agent 定义
        List<AgentDefinition> definitions = definitionLoader.getAgentDefinitions();
        if (definitions.isEmpty()) {
            log.warn("未找到 Agent 定义，请检查 agent-definitions/ 目录");
            running = true;
            return;
        }

        // 先初始化所有 Agent 的工作空间目录
        initializeWorkspaces(definitions);

        // 先创建非编排型 Agent，因为编排型 Agent 可能依赖它们
        for (AgentDefinition def : definitions) {
            if (!isOrchestrated(def))
                initializeSingleAgent(def);
        }

        // 再创建编排型 Agent（Supervisor/Pipeline/Routing）
        for (AgentDefinition def : definitions) {
            if (isOrchestrated(def))
                createOrchestratedAgent(def);
        }

        log.info("Agent 初始化完成，共 {} 个 Agent", agentService.countAgents());
        bootstrapMcpCoordinator();
        running = true;
    }

    /**
     * 停止 Agent 配置器，设置运行标志为 false。
     */
    @Override
    public void stop() {
        if (!running)
            return;
        log.info("AgentConfigurer 关停...");
        running = false;
    }

    /**
     * 查询配置器是否正在运行。
     *
     * @return true 表示已启动
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取生命周期阶段，phase=5 确保在基础 Bean 初始化后执行。
     *
     * @return 生命周期阶段值
     */
    @Override
    public int getPhase() {
        return 5;
    }

    /**
     * 是否自动启动，返回 true 表示 Spring 容器启动时自动调用 start()。
     *
     * @return true 自动启动
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 带回调的停止方法，执行 stop() 后通知回调。
     *
     * @param callback 停止完成后的回调
     */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    // ========== 工作空间初始化 ==========

    /**
     * 初始化所有 Agent 的工作空间。
     *
     * <p>为每个 Agent 创建其专属 workspace 目录并在其中写入 AGENTS.md。
     * 目录结构如下（仅 yunxi 侧的内容，子目录由框架按需懒创建）：</p>
     *
     * <pre>
     * .agentscope/workspace/
     * ├── skills/                         ← projectGlobalSkillsDir（Layer 1）
     * └── agents/{name}/
     *     ├── AGENTS.md                   ← 本方法生成：Agent persona + 可用资源
     *     ├── sessions/                   ← 框架懒创建
     *     ├── knowledge/                  ← 框架懒创建
     *     └── workspace/                  ← ISOLATED subagent 时框架创建
     * </pre>
     *
     * <p>AGENTS.md 的作用：框架 WorkspaceContextMiddleware 在每次 agent.call() 时读取它，
     * 作为 {@code <agents_context>} 注入 system prompt 末尾，告诉 LLM 其 persona 和
     * 本地约定。Subagent 的 system prompt 也优先取自其 workspace 下的 AGENTS.md。
     * 因此本生成逻辑对业务有实际价值，不交给框架托管。</p>
     *
     * @param definitions Agent 定义列表
     */
    private void initializeWorkspaces(List<AgentDefinition> definitions) {
        for (AgentDefinition def : definitions) {
            String workspacePath = coreProperties.getWorkspaceBasePath() + "/agents/" + def.getName();
            ensureAgentsMd(def.getName(), description(def), def.getPrompt(), workspacePath);
        }
    }

    /**
     * 为单个 Agent 生成 AGENTS.md（已存在则跳过）。
     *
     * <p>该文件位于每个 Agent workspace 根目录
     * （{@code .agentscope/workspace/agents/{name}/AGENTS.md}），
     * 内容包含：id/name frontmatter、描述、系统提示词、工作空间可用资源说明。
     * 框架 {@code WorkspaceManager.readAgentsMd()} →
     * {@code WorkspaceContextMiddleware.onSystemPrompt()} 在每次对话中自动注入。</p>
     *
     * @param agentName       Agent 名称
     * @param displayName    显示名称
     * @param sysPrompt      系统提示词
     * @param workspacePath  工作空间路径
     */
    private void ensureAgentsMd(String agentName, String displayName, String sysPrompt, String workspacePath) {
        try {
            Path dir = Path.of(workspacePath);
            Path agentsMd = dir.resolve("AGENTS.md");
            if (java.nio.file.Files.notExists(agentsMd)) {
                java.nio.file.Files.createDirectories(dir);
                String content = "---\n"
                        + "id: " + agentName + "\n"
                        + "name: " + (displayName != null ? displayName : agentName) + "\n"
                        + "---\n\n"
                        + "## 描述\n\n我是 " + (displayName != null ? displayName : agentName) + "。\n\n"
                        + "## 系统提示词\n\n" + (sysPrompt != null ? sysPrompt : "") + "\n\n"
                        + "## 工作空间可用资源\n\n"
                        + "- 函数 `read_file` 读取 knowledge/ 目录下的知识文件\n"
                        + "- 函数 `memory_search` 搜索记忆内容\n"
                        + "- 函数 `agent_spawn` 创建子 Agent 并执行任务\n"
                        + "- 请勿修改 AGENTS.md 和 MEMORY.md 文件\n\n";
                java.nio.file.Files.writeString(agentsMd, content);
                log.info("生成 AGENTS.md: {}", agentsMd);
            }
        } catch (java.io.IOException e) {
            log.warn("生成 AGENTS.md 失败: {} - {}", agentName, e.getMessage());
        }
    }

    /**
     * 判断 Agent 定义是否为编排型。
     *
     * <p>
     * 编排型 Agent 的 orchestration.pattern 不为 null 且不等于 "single"，
     * 包括 supervisor、pipeline、routing 三种模式。
     * </p>
     *
     * @param def Agent 定义
     * @return true 表示是编排型 Agent
     */
    private boolean isOrchestrated(AgentDefinition def) {
        return def.getOrchestration() != null && !"single".equals(def.getOrchestration().getPattern());
    }

    // ========== 创建单 Agent ==========

    /**
     * 创建并注册单个独立 Agent。
     *
     * <p>
     * 核心创建流程：
     * 1. 通过 ModelFactory 创建 LLM 模型
     * 2. 构建 Toolkit（工具集）
     * 3. 配置 HarnessAgent Builder（名称、Prompt、模型、工具、工作空间等）
     * 4. 配置 Middleware、Runtime、Plan
     * 5. 应用 AgentCustomizer SPI 扩展
     * 6. 激活工具组
     * 7. 注册到 AgentService
     * </p>
     *
     * @param def Agent 定义配置
     */
    /** 若启用了 MUSE（yunxi.muse.enabled=true），把自进化元工具组挂入 toolkit。 */
    private void registerMuseTools(Toolkit toolkit) {
        MuseToolRegistrar muse = museToolRegistrarProvider.getIfAvailable();
        if (muse != null) {
            muse.registerTools(toolkit);
        }
    }

    private void initializeSingleAgent(AgentDefinition def) {
        try {
            log.info("创建 Agent: {}", def.getName());
            // 创建 LLM 模型实例
            Model model = modelFactory.create(def.getModel());
            // 构建工具集
            Toolkit toolkit = buildToolkit(def);
        registeredToolkits.add(toolkit);
            // 按 Agent 配置注册 MCP 服务器工具（框架原生 McpClientBuilder / SSE）
            registerMcpServers(toolkit, def);
            registerMuseTools(toolkit);

            // 激活工具组（toolkit 共享，仅执行一次）
            applyToolGroupActivation(toolkit, def);

            // 基础实例：Agent 默认提示词
            buildAndRegisterAgent(def, def.getName(), def.getPrompt(), model, toolkit);

            // Profile 实例：每个 profile 生成独立 sysPrompt 的 Agent 实例，
            // 以 组合键(agentName#profileName) 注册，供 ProfileRouter.resolve 精确路由
            Map<String, ProfileDefinition> profiles = def.getProfiles();
            if (profiles != null && !profiles.isEmpty()) {
                for (Map.Entry<String, ProfileDefinition> entry : profiles.entrySet()) {
                    String profileName = entry.getKey();
                    ProfileDefinition pd = entry.getValue();
                    if (pd == null) {
                        continue;
                    }
                    String profilePrompt = resolveProfilePrompt(def, pd);
                    buildAndRegisterAgent(def, def.getName() + "#" + profileName, profilePrompt, model, toolkit);
                    log.info("创建 Profile 实例: {}#{} (prompt 覆盖={})", def.getName(), profileName,
                            pd.getPrompt() != null || pd.getPromptSuffix() != null);
                }
            }

            // 注册 Agent 元信息（仅基础实例；Profile 实例为内部路由实体，不进入 Agent 列表）
            agentService.registerAgentInfoDto(def.getName(), description(def), def.getPrompt(),
                    def.getModel() != null ? def.getModel().getModelName() : coreProperties.getModelName());
            agentService.registerAgentRagMode(def.getName(), def.getRagMode());

            log.info("Agent 创建成功: {}, ragMode={}, profiles={}", def.getName(), def.getRagMode(),
                    profiles == null ? 0 : profiles.size());
        } catch (Throwable e) {
            // 捕获 Throwable 而非 Exception：agentscope 框架内部类初始化可能抛出
            // NoClassDefFoundError / ExceptionInInitializerError（均继承 Error），
            // 若不被捕获会冲破 Spring 生命周期导致 stopBeans() 级联失败
            log.error("Agent 创建失败: {}", def.getName(), e);
        }
    }

    /**
     * 构建并注册一个 Agent 实例（基础实例与 Profile 实例共用同一构建链路）。
     *
     * @param def           Agent 定义配置
     * @param instanceName  实例名：基础实例为 def.getName()，Profile 实例为 agentName#profileName
     * @param prompt        实例使用的系统提示词（基础=def.getPrompt()，Profile=resolveProfilePrompt 解析结果）
     * @param model         共享 LLM 模型实例
     * @param toolkit       共享工具集
     */
    private void buildAndRegisterAgent(AgentDefinition def, String instanceName, String prompt,
                                       Model model, Toolkit toolkit) {
        // 配置 HarnessAgent Builder
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(instanceName).sysPrompt(prompt).model(model).toolkit(toolkit)
                .workspace(coreProperties.getWorkspaceBasePath() + "/agents/" + def.getName())
                .compaction(buildCompactionConfig());

        // 配置 Middleware 链与运行时参数
        configureMiddlewares(builder, def, toolkit);
        configureRuntime(builder, def);

        // 配置分布式后端（可选）：stateStore + baseStore + snapshotSpec 一站式配置
        if (distributedBackend != null)
            builder.distributedStore(distributedBackend);

        // 配置 AgentScope 原生能力：计划模式 / 任务清单 / 技能系统 / 韧性（重试-降级-超时）
        // 全部复用 HarnessAgent.Builder 原生 API，不自建任何等价逻辑
        configurePlan(builder, def);
        configureTaskList(builder, def);
        configureSkills(builder, def);
        configureResilience(builder, def);

        // 应用 AgentCustomizer SPI 扩展（如有）
        AgentCustomizer customizer = findCustomizer(def);
        Agent agent = customizer != null ? customizer.customize(def, builder.build()) : builder.build();

        agentService.registerAgentInstance(instanceName, agent);
    }

    /**
     * 解析 Profile 实例的系统提示词，优先级：profile.prompt &gt; base + profile.promptSuffix &gt; base。
     *
     * @param def  Agent 定义配置
     * @param pd   Profile 定义
     * @return Profile 实例使用的系统提示词
     */
    private String resolveProfilePrompt(AgentDefinition def, ProfileDefinition pd) {
        if (pd.getPrompt() != null && !pd.getPrompt().isBlank()) {
            return pd.getPrompt();
        }
        if (pd.getPromptSuffix() != null && !pd.getPromptSuffix().isBlank()) {
            return def.getPrompt() + "\n\n" + pd.getPromptSuffix();
        }
        return def.getPrompt();
    }

    // ========== 编排 Agent ==========

    /**
     * 根据编排模式创建编排型 Agent。
     *
     * <p>
     * 支持三种编排模式：
     * - supervisor：监督者模式，由 Supervisor Agent 调度子 Agent
     * - pipeline：流水线模式，Agent 按阶段顺序执行
     * - routing：路由模式，根据输入选择合适的 Agent
     * </p>
     *
     * @param def Agent 定义配置
     */
    private void createOrchestratedAgent(AgentDefinition def) {
        String pattern = def.getOrchestration().getPattern();
        log.info("创建编排 Agent: {} (pattern={})", def.getName(), pattern);
        switch (pattern) {
            case "supervisor" -> createSupervisorAgent(def);
            case "pipeline" -> createPipelineAgent(def);
            case "routing" -> createRoutingAgent(def);
            default -> log.warn("不支持的编排模式: {} (Agent: {})", pattern, def.getName());
        }
    }

    /**
     * 创建 Supervisor 编排 Agent。
     *
     * <p>
     * Supervisor 模式：一个 Supervisor Agent 管理多个子 Agent（专家），
     * 通过 SubAgentConfig 将子 Agent 注册为工具，Supervisor 根据用户意图
     * 选择合适的子 Agent 来执行任务。
     * </p>
     *
     * @param def Agent 定义配置
     */
    private void createSupervisorAgent(AgentDefinition def) {
        List<ExpertConfig> experts = def.getOrchestration().getExperts();
        if (experts == null || experts.isEmpty()) {
            log.warn("Supervisor [{}] 未配置专家", def.getName());
            return;
        }
        // 收集所有专家 Agent 实例
        Map<String, Agent> expertAgents = new HashMap<>();
        for (ExpertConfig expert : experts) {
            try {
                Agent agent = agentService.getAgentInstance(expert.getName());
                expertAgents.put(expert.getName(), agent);
            } catch (Exception e) {
                log.warn("获取 Agent 失败: {} (Supervisor: {})", expert.getName(), def.getName());
            }
        }
        if (expertAgents.isEmpty()) {
            log.warn("Supervisor [{}] 没有可用的 Agent", def.getName());
            return;
        }

        // 创建 Supervisor 的模型和工具集
        Model model = modelFactory.create(def.getModel());
        Toolkit toolkit = new Toolkit();
        // 创建 "agent" 工具组，用于存放子 Agent 工具
        toolkit.createToolGroup("agent", "子 Agent 工具组", true);
        // 按 Agent 配置注册 MCP 服务器工具（若有）
        registerMcpServers(toolkit, def);
        registerMuseTools(toolkit);
        // 将每个专家 Agent 注册为子 Agent 工具
        for (ExpertConfig expert : experts) {
            Agent agent = expertAgents.get(expert.getName());
            if (agent == null) {
                continue;
            }
            // forwardEvents 默认 true：透出子 Agent 事件到 Supervisor 流，前端可完整观测；
            // 专家可在 YAML 中设 forwardEvents: false 抑制子 Agent 内部明细。
            boolean forward = expert.getForwardEvents() == null || expert.getForwardEvents();
            toolkit.registration()
                    .subAgent(() -> agent,
                            SubAgentConfig.builder().forwardEvents(forward).build())
                    .group("agent").apply();
        }

        // 激活工具组并注册（toolkit 共享，仅执行一次）
        applyToolGroupActivation(toolkit, def);

        // 基础实例：Supervisor 默认提示词
        buildSupervisorInstance(def, def.getName(), def.getPrompt(), model, toolkit);

        // Profile 实例：每个 profile 生成独立 sysPrompt 的 Supervisor 实例，
        // 以 组合键(agentName#profileName) 注册，供 ProfileRouter.resolve 精确路由
        Map<String, ProfileDefinition> profiles = def.getProfiles();
        if (profiles != null && !profiles.isEmpty()) {
            for (Map.Entry<String, ProfileDefinition> entry : profiles.entrySet()) {
                String profileName = entry.getKey();
                ProfileDefinition pd = entry.getValue();
                if (pd == null) {
                    continue;
                }
                String profilePrompt = resolveProfilePrompt(def, pd);
                buildSupervisorInstance(def, def.getName() + "#" + profileName, profilePrompt, model, toolkit);
                log.info("创建 Supervisor Profile 实例: {}#{} (prompt 覆盖={})", def.getName(), profileName,
                        pd.getPrompt() != null || pd.getPromptSuffix() != null);
            }
        }

        // 注册元信息（仅基础实例；Profile 实例为内部路由实体，不进入 Agent 列表）
        agentService.registerAgentInfoDto(def.getName(), description(def), def.getPrompt(),
                def.getModel() != null ? def.getModel().getModelName() : coreProperties.getModelName());
        agentService.registerAgentRagMode(def.getName(), def.getRagMode());
        log.info("Supervisor Agent 创建完成: {}, 专家数: {}, profiles={}", def.getName(), expertAgents.size(),
                profiles == null ? 0 : profiles.size());
    }

    /**
     * 构建并注册一个 Supervisor 实例（基础实例与 Profile 实例共用同一构建链路）。
     *
     * @param def           Agent 定义配置
     * @param instanceName  实例名：基础实例为 def.getName()，Profile 实例为 agentName#profileName
     * @param prompt        实例使用的系统提示词
     * @param model         共享 LLM 模型实例
     * @param toolkit       共享工具集（含已注册的子 Agent 工具）
     */
    private void buildSupervisorInstance(AgentDefinition def, String instanceName, String prompt,
                                         Model model, Toolkit toolkit) {
        // 配置 Supervisor Agent Builder
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(instanceName).sysPrompt(prompt).model(model).toolkit(toolkit)
                .workspace(coreProperties.getWorkspaceBasePath() + "/agents/" + def.getName())
                .compaction(buildCompactionConfig());

        configureMiddlewares(builder, def, toolkit);
        configureRuntime(builder, def);

        // 配置分布式后端（可选）
        if (distributedBackend != null)
            builder.distributedStore(distributedBackend);

        // 配置 AgentScope 原生能力：计划模式 / 任务清单 / 技能系统 / 韧性（与单 Agent 一致，完全复用框架）
        configurePlan(builder, def);
        configureTaskList(builder, def);
        configureSkills(builder, def);
        configureResilience(builder, def);

        // 应用 AgentCustomizer SPI 扩展
        AgentCustomizer customizer = findCustomizer(def);
        Agent supervisor = customizer != null ? customizer.customize(def, builder.build()) : builder.build();

        agentService.registerAgentInstance(instanceName, supervisor);
    }

    /**
     * 创建 Pipeline 编排 Agent。
     *
     * <p>
     * Pipeline 模式：Agent 按照预定义的阶段顺序执行，
     * 当前仅验证各阶段的 Agent 是否存在，实际调度由上层逻辑处理。
     * </p>
     *
     * @param def Agent 定义配置
     */
    private void createPipelineAgent(AgentDefinition def) {
        List<StageConfig> stages = def.getOrchestration().getStages();
        if (stages == null || stages.isEmpty())
            return;
        // 验证每个阶段的 Agent 是否已注册
        for (StageConfig s : stages) {
            try {
                agentService.getAgentInstance(s.getAgent());
            } catch (Exception e) {
                log.warn("Pipeline [{}] 的 Agent 不存在: {}", def.getName(), s.getAgent());
            }
        }
        log.info("Pipeline [{}] 验证通过，共 {} 个阶段", def.getName(), stages.size());
    }

    /**
     * 创建 Routing 编排 Agent。
     *
     * <p>
     * Routing 模式：根据输入内容路由到不同的专家 Agent，
     * 当前仅验证路由专家列表是否配置，实际路由逻辑由上层处理。
     * </p>
     *
     * @param def Agent 定义配置
     */
    private void createRoutingAgent(AgentDefinition def) {
        List<ExpertConfig> experts = def.getOrchestration().getExperts();
        if (experts == null || experts.isEmpty())
            return;
        log.info("Routing [{}] 验证通过，共 {} 个路由 Agent", def.getName(), experts.size());
    }

    // ========== Toolkit 构建 ==========

    /**
     * 构建 Agent 的工具集。
     *
     * <p>
     * 创建 Toolkit 并初始化本地工具组；MCP 工具由 {@link #registerMcpServers} 按 Agent 配置
     * 通过框架原生 McpClientBuilder 注册（SSE/STDIO/HTTP），确保配置与实际注册一致。
     * </p>
     *
     * @param def Agent 定义配置
     * @return 初始化了本地工具组的 Toolkit 实例
     */
    private Toolkit buildToolkit(AgentDefinition def) {
        Toolkit toolkit = new Toolkit();
        createLocalToolGroups(toolkit);
        return toolkit;
    }

    /**
     * 创建本地工具组。
     *
     * <p>
     * 预定义 6 个标准工具组：agent（子Agent）、memory（记忆检索）、
     * filesystem（文件系统）、execute（命令执行）、page（页面操作）、general（通用）。
     * 工具组创建失败时静默忽略（可能已有同名工具组）。
     * </p>
     *
     * @param toolkit Toolkit 实例
     */
    private void createLocalToolGroups(Toolkit toolkit) {
        for (String[] g : new String[][] {
                { "agent", "子 Agent 工具组" },
                { "memory", "记忆检索工具" },
                { "filesystem", "文件系统工具" },
                { "execute", "命令执行工具" },
                { "page", "页面操作工具" },
                { "general", "通用工具" } }) {
            try {
                toolkit.createToolGroup(g[0], g[1], true);
            } catch (Exception ignored) {
                // 同名工具组可能已存在，静默忽略
            }
        }
    }

    // ========== MCP 工具注册 ==========

    /**
     * 按 Agent 配置注册 MCP 服务器工具到 Toolkit。
     *
     * <p>复用框架原生
     * {@link McpClientBuilder} 按 {@code type}（sse/stdio/http）实例化 {@link McpClientWrapper}，
     * 并以服务器名作为工具组名注册进 Toolkit，从而：1) 完全复用底层框架 MCP 能力，无任何自建协议层；
     * 2) 与 {@code tools.mcpServers} / {@code toolsGroup.mcpServersToolsGroup} 分组激活无缝衔接。
     * 单个服务器注册失败仅记录日志，不影响其余服务器与 Agent 启动（由外壳 try/catch 保证）。</p>
     *
     * @param toolkit 目标 Toolkit
     * @param def     Agent 定义配置
     */
    private void registerMcpServers(Toolkit toolkit, AgentDefinition def) {
        Map<String, AgentscopeCoreProperties.McpServerConfig> all = coreProperties.getMcpServers();
        if (all == null || all.isEmpty()) {
            return;
        }

        // 收集该 Agent 需要加载的 MCP 服务器名（去重）
        Set<String> wanted = new LinkedHashSet<>();
        if (def.getTools() != null && def.getTools().getMcpServers() != null) {
            wanted.addAll(def.getTools().getMcpServers());
        }
        if (def.getToolsGroup() != null && def.getToolsGroup().getMcpServersToolsGroup() != null) {
            wanted.addAll(def.getToolsGroup().getMcpServersToolsGroup());
        }
        if (wanted.isEmpty()) {
            return;
        }

        for (String name : wanted) {
            AgentscopeCoreProperties.McpServerConfig src = all.get(name);
            if (src == null) {
                log.warn("MCP 服务器 '{}' 未配置，跳过注册", name);
                continue;
            }
            if (!src.isEnabled()) {
                log.debug("MCP 服务器 '{}' 已禁用，跳过注册", name);
                continue;
            }
            try {
                // 按服务器名复用已建立的连接，避免每个 Agent 重复建连
                // 用 yunxi 层断线重连包装器包裹底层 wrapper（AgentScope MCP SDK 0.9.0 无原生重连，待 AgentScope 升级后移除）
                McpClientWrapper wrapper =
                        mcpClientCache.computeIfAbsent(name, n -> wrapWithReconnect(n, src));
                // 预建以服务器名命名的工具组（默认不激活，由 applyToolGroupActivation 按配置控制可见性）
                try {
                    toolkit.createToolGroup(name, "MCP 服务器: " + name, false);
                } catch (Exception ignored) {
                    // 组已存在则忽略
                }
                toolkit.registration().mcpClient(wrapper).group(name).apply();
                log.info("MCP 服务器 '{}' 工具已注册进工具组 '{}'", name, name);
            } catch (Exception e) {
                log.error(
                        "[MCP] 服务器 '{}'（{} {}）注册失败：{}。"
                                + " 若是服务未启动，请先启动对应 MCP 服务（配置 agentscope.core.mcp-servers.{}）后再重试；"
                                + " 该 Agent 的其余工具不受影响。",
                        name, src.getType(), src.getUrl(), e.getMessage(), name);
            }
        }
    }

    /**
     * 用框架原生 {@link McpClientBuilder} 按传输类型构建 MCP 客户端。
     *
     * @param name 服务器名（同时作为工具组名）
     * @param src  服务器配置（来自 agentscope.core.mcp-servers）
     * @return 已建立连接的 McpClientWrapper
     */
    /**
     * yunxi 层临时适配：将底层 MCP wrapper 包入断线自动重连包装器。
     * 底层框架（AgentScope）升级到内置原生 reconnect 的 MCP SDK（>= 0.10.0）后，
     * 把 {@code registerMcpServers} 中对本方法的调用还原为直接 {@code buildMcpClient} 即可删除本类。
     */
    private McpClientWrapper wrapWithReconnect(String name, AgentscopeCoreProperties.McpServerConfig src) {
        return new ReconnectingMcpClientWrapper(name, () -> buildMcpClient(name, src));
    }

    private McpClientWrapper buildMcpClient(String name, AgentscopeCoreProperties.McpServerConfig src) {
        String type = src.getType() == null ? "sse" : src.getType();
        // 连接预检：用最朴素的 TCP 探测目标 host:port 是否可达，使"MCP 服务器未启动"这类问题
        // 在日志中明确指向具体服务器与地址，而不是被框架内部 reactor 的 onErrorDropped 吞成
        // 一条无上下文的 ConnectException。返回 false 表示端口明确不可达。
        boolean reachable = probeConnection(name, type, src.getUrl());

        McpClientBuilder builder = McpClientBuilder.create(name);
        switch (type) {
            case "stdio" -> builder.stdioTransport(src.getCommand(), src.getArgs(), src.getEnv());
            case "sse" -> {
                builder.sseTransport(src.getUrl());
                if (src.getHeaders() != null && !src.getHeaders().isEmpty()) {
                    builder.headers(src.getHeaders());
                }
            }
            case "http", "streamable-http", "streamablehttp" -> {
                builder.streamableHttpTransport(src.getUrl());
                if (src.getHeaders() != null && !src.getHeaders().isEmpty()) {
                    builder.headers(src.getHeaders());
                }
            }
            default -> throw new IllegalArgumentException("未知 MCP 传输类型: " + type);
        }
        if (src.getTimeout() != null) {
            builder.timeout(Duration.ofMillis(src.getTimeout()));
        }
        McpClientWrapper wrapper = builder.buildAsync().block();
        // 仅当端口可达时，主动同步触发一次握手初始化，把握手阶段的连接/协议异常同步捕获
        // 并记录具体日志（初始化幂等，框架随后再次 initialize 无副作用）。端口不通时不做此
        // 探测，避免无谓的连接超时等待。无论初始化成败均返回 wrapper，运行时 callTool 失败会
        // 由 ReconnectingMcpClientWrapper 自动重连。
        if (reachable) {
            try {
                wrapper.initialize().block(java.time.Duration.ofSeconds(3));
            } catch (Exception e) {
                log.warn(
                        "[MCP] 服务器 '{}'（{} {}）握手初始化失败：{}。"
                                + " 请先启动对应的 MCP 服务（配置 agentscope.core.mcp-servers.{}，地址 {}），"
                                + " 否则该 Agent 的 MCP 工具暂时不可用，首次调用时将自动重连。",
                        name, type, src.getUrl(), e.getMessage(), name, src.getUrl());
            }
        }
        return wrapper;
    }

    /**
     * 连接预检：对 SSE / HTTP 类 MCP 服务器，用最朴素的 TCP 连接探测目标 host:port 是否可达。
     * 仅用于诊断——服务器未启动时能明确给出"连不上哪个地址、请先启动哪个服务"的具体日志，
     * 避免被框架内部 reactor 的 onErrorDropped 吞成无上下文的 ConnectException。
     *
     * @return true 表示端口可达（或无需探测的 stdio / 解析异常），false 表示端口明确不可达
     */
    private boolean probeConnection(String name, String type, String url) {
        if (url == null || (!"sse".equals(type) && !"http".equals(type)
                && !"streamable-http".equals(type) && !"streamablehttp".equals(type))) {
            return true;
        }
        String host;
        int port;
        try {
            java.net.URI uri = java.net.URI.create(url);
            host = uri.getHost();
            port = uri.getPort();
            if (port < 0) {
                port = "https".equals(uri.getScheme()) ? 443 : 80;
            }
        } catch (Exception e) {
            log.warn("[MCP] 服务器 '{}' 的 URL 无法解析，跳过连接预检：{}", name, url);
            return true;
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 2000);
            return true;
        } catch (java.net.ConnectException ce) {
            log.warn(
                    "[MCP] 服务器 '{}' 连接失败：目标 {}:{} 不可达（{}）。"
                            + " 请先启动对应的 MCP 服务（配置 agentscope.core.mcp-servers.{}，地址 {}），"
                            + " 否则该 Agent 的 MCP 工具暂时不可用，首次调用时将自动重连。",
                    name, host, port, ce.getMessage(), name, url);
            return false;
        } catch (Exception e) {
            log.warn("[MCP] 服务器 '{}' 连接预检异常（{}:{}）：{}", name, host, port, e.getMessage());
            return true;
        }
    }

    // ========== Middleware 配置 ==========

    /**
     * 配置 Agent 的 Middleware 链。
     *
     * <p>
     * Middleware 按添加顺序执行，当前配置的 Middleware 顺序：
     * 1. GracefulShutdownMiddleware（优雅关停，必须）
     * 2. ContentFilterMiddleware（内容安全过滤/提示注入检测，必须）
     * 3. OtelTracingMiddleware（可选，GA 原生 OpenTelemetry 链路追踪）
     * 4. HITL：通过框架 {@code permissionContext} 注入
     *    并保留 HumanToolRegistrar（人工协作工具注册）
     * </p>
     *
     * <p>工具调用解析交由框架内部机制处理，平台不再单独实现。</p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     * @param toolkit 工具集
     */
    private void configureMiddlewares(HarnessAgent.Builder builder, AgentDefinition def, Toolkit toolkit) {
        // Studio 调试已内置，无需手动注入 Studio Hook

        // 必须：优雅关停 Middleware
        builder.middleware(new GracefulShutdownMiddleware(GracefulShutdownManager.getInstance()));
        // 必须：内容安全过滤（提示注入检测）
        builder.middleware(new ContentFilterMiddleware());

        // 可选：GA 原生 OpenTelemetry 链路追踪（invoke_agent / chat / execute_tool 三段 span）
        if (otelTracingMiddlewareProvider.getIfAvailable() != null) {
            builder.middleware(otelTracingMiddlewareProvider.getIfAvailable());
        }

        // 可选：HITL（人机交互）—— AgentScope 原生权限上下文 + 人工协作工具
        injectHITLMiddlewares(builder, def);

        // 构建期工具引用校验（best-effort）：allowedTools/deniedTools 是否存在于已注册 toolkit
        validateToolReferences(def, toolkit);
    }

    /**
     * 注入 HITL（Human-In-The-Loop）能力。
     *
     * <p>
     * 工具门控（ToolGate）与推理审查（ReasoningReview）的"执行特定工具前需人工确认"语义，
     * 统一交由框架原生权限引擎处理：
     * 通过 {@link PermissionConfig} 将 HITL 配置映射为 {@code PermissionContextState}，
     * 以 {@code builder.permissionContext(...)} 注入（被点名的工具执行前会挂起请求确认）。
     * </p>
     *
     * <p>此外保留 {@code HumanToolRegistrar}（人工协作工具注册），它仅注册 ToolSchema，不依赖具体 Toolkit。</p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     */
    private void injectHITLMiddlewares(HarnessAgent.Builder builder, AgentDefinition def) {
        if (def == null)
            return;
        ExtensionConfig extensions = def.getExtensions();
        // 未配置 HITL（无人值守安全场景）：AgentScope 框架默认权限模式为 DEFAULT，
        // 在无任何 ASK 规则时会对所有工具调用返回 PERMISSION_ASKING（挂起等人工确认），
        // 导致纯查询/评分类 Agent（如营养配餐）在无前端确认弹窗的自动生成路径上永久卡死。
        // 因此显式注入 DONT_ASK 上下文：把兜底的 ASK 降级为 DENY，从根本上杜绝挂起；
        // 写操作与危险路径一律拒绝，保护由框架层兜底（ToolBase 内部敏感路径拦截）。
        //
        // 注意（实测行为，勿按字面误解）：DONT_ASK 下未命中 ALLOW 规则的工具一律被拒绝，
        // 包括 list_files / read_file 等只读工具——它们的 readOnly=true 在本模式下不生效，
        // 因为该属性仅在 EXPLORE / ACCEPT_EDITS 模式下被判定。
        // 为何不用 EXPLORE（语义上更贴近"只读放行"）：其判定早于 ALLOW 规则且立即返回，
        // 会使 readOnly=false 的 todo_write 被直接 DENY，导致任务清单功能失效。
        // 完整权衡见 PermissionConfig#unattendedContext。
        // 注：McpTool 在 DONT_ASK 下并不会被框架自检自动放行，业务工具需在 HITL.allowedTools 中显式声明。
        if (extensions == null || extensions.getHitl() == null) {
            builder.permissionContext(permissionConfig.unattendedContext());
            return;
        }

        var hitlConfig = extensions.getHitl();
        if (PermissionConfig.hasAskTools(hitlConfig)) {
            // 注入了需人工确认工具 → DEFAULT 模式，执行前挂起向用户确认
            builder.permissionContext(permissionConfig.build(hitlConfig, PermissionMode.DEFAULT));
        } else if (hitlConfig.getAllowedTools() != null && !hitlConfig.getAllowedTools().isEmpty()) {
            // 声明了无人值守可调用工具白名单 → DONT_ASK + 显式 ALLOW，避免业务工具被兜底 DENY
            builder.permissionContext(permissionConfig.unattendedContext(hitlConfig.getAllowedTools()));
        } else {
            // 未配置白名单 → 黑名单模式（BYPASS 默认全放行，仅拒绝 deniedTools 中显式列出的工具）。
            // 不传入平台级基线：未在黑名单中列出的工具（如 node_command）一律放行，
            // 只有 Agent 自己在 deniedTools 里写明的才被 DENY。适用于"禁止的少、允许的多"。
            builder.permissionContext(permissionConfig.blacklistContext(hitlConfig.getDeniedTools(), null));
        }

        // 注册人工工具（HumanTool 注册当前仅支持 schema 注册，无需绑定具体 Toolkit）
        if (hitlConfig.getHumanTool() != null && hitlConfig.getHumanTool().isEnabled()) {
            // HumanToolRegistrar 仅注册 ToolSchema，可在没有 Toolkit 的情况下工作
            // registerTools(null) 会跳过注册（registerTools 内有 null guard）
            new HumanToolRegistrar(hitlConfig.getHumanTool()).registerTools(null);
        }
    }

    /**
     * 构建期工具引用校验（best-effort）。
     *
     * <p>将 HITL 中声明的 allowedTools / deniedTools 与当前 toolkit 已注册工具名比对，
     * 对不存在的工具名输出 WARN 日志，便于在启动时即发现"提示词/白名单写了不存在的工具名"
     * 这类配置错误（例如把 search_public_dishes 错写成 search_dishes 会导致工具调用 Unauthorized）。</p>
     *
     * <p>注意：MCP 工具可能在构建期尚未完成异步连接，故未命中仅告警、不阻断启动；
     * 运行时若工具仍不存在，AgentScope 会在调用时返回错误，Agent 可据此降级。</p>
     *
     * @param def     Agent 定义
     * @param toolkit 已构建的工具集
     */
    private void validateToolReferences(AgentDefinition def, Toolkit toolkit) {
        ExtensionConfig extensions = def == null ? null : def.getExtensions();
        HITLConfig hitl = extensions == null ? null : extensions.getHitl();
        if (hitl == null || toolkit == null) {
            return;
        }
        Set<String> registered = toolkit.getToolNames();
        List<String> refs = new ArrayList<>();
        if (hitl.getAllowedTools() != null) {
            refs.addAll(hitl.getAllowedTools());
        }
        if (hitl.getDeniedTools() != null) {
            refs.addAll(hitl.getDeniedTools());
        }
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            if (!registered.contains(ref)) {
                log.warn("[权限] Agent「{}」引用的工具名「{}」未在当前 toolkit 注册（可能名称拼写错误，"
                        + "或 MCP 工具尚未异步连接）。若运行时仍不可用，工具调用将被框架拒绝。",
                        def.getName(), ref);
            }
        }
    }

    // ========== Runtime / Plan 配置 ==========

    /**
     * 配置 Agent 运行时参数。
     *
     * <p>
     * 包括最大迭代次数（maxIters）和 MetaTool 开关。
     * maxIters 限制 ReAct 循环的最大迭代次数，防止无限循环。
     * enableMetaTool 启用后 Agent 可以动态管理自己的工具。
     * </p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     */
    private void configureRuntime(HarnessAgent.Builder builder, AgentDefinition def) {
        if (def.getRuntime() != null) {
            builder.maxIters(def.getRuntime().getMaxIterations());
            if (def.getRuntime().isEnableMetaTool()) {
                builder.enableMetaTool(true);
            }
        }
    }

    /**
     * 配置 Agent 规划功能（AgentScope PlanMode）。
     *
     * <p>
     * 计划能力完全由
     * AgentScope {@link PlanModeMiddleware} + {@link PlanModeManager} 托管。
     * 启用条件取二者之一：YAML 的 {@code plan.enabled=true} 或 全局
     * {@code agentscope.core.plan.enabled=true}。
     * {@link PlanModeManager} 需要一个 {@link WorkspaceManager}，复用与
     * {@code .workspace(path)} 相同的绝对路径构造，确保计划文件落在该 Agent 工作区内。
     * </p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     */
    private void configurePlan(HarnessAgent.Builder builder, AgentDefinition def) {
        boolean yamlEnabled = def.getPlan() != null && def.getPlan().isEnabled();
        boolean globalEnabled = coreProperties.getPlan().isEnabled();
        if (!yamlEnabled && !globalEnabled) {
            return;
        }

        // 复用与 .workspace(path) 一致的绝对路径，构造 AgentScope 原生 WorkspaceManager
        String workspacePath = coreProperties.getWorkspaceBasePath() + "/agents/" + def.getName();
        WorkspaceManager workspaceManager = new WorkspaceManager(Path.of(workspacePath));
        String planDir = coreProperties.getPlan().getPlanDir();

        // 只读解析器：将配置中的只读工具关键字（逗号分隔）编译为 Predicate，
        // plan 模式下仅允许这些只读工具 + AgentScope 内置 plan 控制工具（如 plan_write）。
        Predicate<String> readOnlyResolver = buildReadOnlyResolver(coreProperties.getPlan().getReadOnlyTools());

        builder.middleware(new PlanModeMiddleware(
                new PlanModeManager(workspaceManager, planDir), readOnlyResolver));
        log.info("Agent '{}' 已启用 AgentScope PlanMode（planDir={}）", def.getName(), planDir);
    }

    /**
     * 配置 Agent 任务清单能力（AgentScope 原生 TodoList）。
     *
     * <p>
     * 能力完全由 AgentScope 提供：{@code ReActAgent.Builder.enableTaskList(true)} 在 build
     * 阶段注册 {@code todo_write} 工具（操作 {@code AgentState.tasksContext}，全量替换语义）
     * 与 {@code TaskReminderMiddleware}（每轮推理前重新注入任务列表）。yunxi 不自建工具、
     * 不自建存储、不新增状态机。
     * </p>
     *
     * <p>
     * 启用条件与 {@link #configurePlan} 一致，取二者之一：YAML 的
     * {@code plan.taskList=true} 或 全局 {@code agentscope.core.plan.task-list=true}。
     * 任务清单与 PlanMode 相互独立——{@code todo_write} 不要求先进入计划模式。
     * </p>
     *
     * <p>
     * 任务状态持久化于 {@code AgentState.tasksContext}，随 AgentState 由
     * {@code AgentStateStore} 按 (userId, sessionId) 槽位保存，跨会话续传天然具备。
     * 默认即有文件持久化——HarnessAgent 在未显式设置时自动装配
     * {@code JsonFileAgentStateStore}（{@code ~/.agentscope/state/<agentId>/}）；
     * Redis 仅在跨实例/多副本部署时需要。
     * </p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     */
    private void configureTaskList(HarnessAgent.Builder builder, AgentDefinition def) {
        boolean yamlEnabled = def.getPlan() != null && def.getPlan().isTaskList();
        boolean globalEnabled = coreProperties.getPlan().isTaskList();
        if (!yamlEnabled && !globalEnabled) {
            return;
        }
        builder.enableTaskList(true);
        log.info("Agent '{}' 已启用 AgentScope 任务清单（todo_write + TaskReminderMiddleware）", def.getName());
    }

    /**
     * 将配置中的只读工具关键字编译为工具名 Predicate。
     *
     * @param readOnlyTools 逗号分隔的工具名关键字（可空）
     * @return 判定某工具是否只读的谓词（空配置返回始终 false，交由 AgentScope 默认策略）
     */
    private Predicate<String> buildReadOnlyResolver(String readOnlyTools) {
        if (readOnlyTools == null || readOnlyTools.isBlank()) {
            return name -> false;
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (String kw : readOnlyTools.split("[,，]")) {
            String t = kw.trim();
            if (!t.isEmpty()) {
                keywords.add(t.toLowerCase());
            }
        }
        if (keywords.isEmpty()) {
            return name -> false;
        }
        return name -> {
            if (name == null) return false;
            String lower = name.toLowerCase();
            return keywords.stream().anyMatch(lower::contains);
        };
    }

    /**
     * 配置 AgentScope 原生技能系统（AgentSkillRepository）。
     *
     * <p>
     * 框架未提供 {@code builder.skillSystem(...)} 配置入口，
     * 改用 AgentScope 原生技能仓库体系。注册任意 {@link AgentSkillRepository} 即可自动装载
     * {@code DynamicSkillMiddleware}，由框架负责技能加载、可见性过滤与自学习闭环。
     * 这里优先用文件系统技能目录（{@code agentscope.core.skill.filesystem-dir}），
     * 可选叠加项目级全局技能目录（{@code projectGlobalSkillsDir}）。
     * </p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     */
    private void configureSkills(HarnessAgent.Builder builder, AgentDefinition def) {
        AgentscopeCoreProperties.SkillProperties skill = coreProperties.getSkill();
        if (!skill.isEnabled()) {
            return;
        }
        // 使用框架原生 Layer 1：项目级全局技能目录。
        // 框架的 composeSkillRepositories() 自动组装四层优先级链：
        //   Layer 1: projectGlobalSkillsDir → Layer 2: skillRepositories
        //   → Layer 3: wsManager.getSkillsDir() → Layer 4: WorkspaceSkillRepository
        // yunxi 不再自建 FileSystemSkillRepository 或预建空目录，完全交给框架。
        if (skill.getProjectGlobalDir() != null && !skill.getProjectGlobalDir().isBlank()) {
            Path pgDir = Path.of(skill.getProjectGlobalDir());
            builder.projectGlobalSkillsDir(pgDir);
            log.info("Agent '{}' 项目级全局技能目录: {}", def.getName(), pgDir);
        }
        log.info("Agent '{}' 技能系统已启用（AgentScope composeSkillRepositories 自动四层装载）", def.getName());
    }

    /**
     * 配置 AgentScope 原生韧性能力（重试 / 降级模型 / 拒绝停止 / 超时）。
     *
     * <p>
     * 完全复用 {@link HarnessAgent.Builder} 原生 API，不自建任何重试/降级/超时逻辑：
     * <ul>
     *   <li>{@code maxRetries} → 模型调用失败重试次数</li>
     *   <li>{@code fallbackModel} → 主模型失败时切换的降级模型 ID</li>
     *   <li>{@code stopOnReject} → 权限被拒时是否停止 Agent</li>
     *   <li>{@code modelExecutionConfig(ExecutionConfig)} → 注入单次执行超时</li>
     * </ul>
     * </p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     */
    private void configureResilience(HarnessAgent.Builder builder, AgentDefinition def) {
        AgentscopeCoreProperties.ResilienceProperties r = coreProperties.getResilience();
        if (r.getMaxRetries() != null) {
            builder.maxRetries(r.getMaxRetries());
        }
        if (r.getFallbackModel() != null && !r.getFallbackModel().isBlank()) {
            builder.fallbackModel(r.getFallbackModel());
        }
        builder.stopOnReject(r.isStopOnReject());
        if (r.getTimeoutMs() != null && r.getTimeoutMs() > 0) {
            builder.modelExecutionConfig(ExecutionConfig.builder()
                    .timeout(java.time.Duration.ofMillis(r.getTimeoutMs()))
                    .build());
        }
    }

    // ========== Compaction ==========

    /**
     * 构建 Memory Compaction 配置。
     *
     * <p>
     * Compaction 机制在对话消息过多时自动压缩历史消息，
     * 防止超出 LLM 上下文窗口限制。配置包括：
     * - triggerMessages：触发压缩的消息数量阈值
     * - triggerTokens：触发压缩的 Token 数量阈值
     * - keepMessages：压缩后保留的最近消息数
     * - flushBeforeCompact：压缩前是否先持久化
     * - offloadBeforeCompact：压缩前是否先卸载到存储
     * </p>
     *
     * @return CompactionConfig 实例
     */
    private CompactionConfig buildCompactionConfig() {
        var c = coreProperties.getCompaction();
        return CompactionConfig.builder()
                .triggerMessages(c.getTriggerMessages())
                .triggerTokens(c.getTriggerTokens())
                .keepMessages(c.getKeepMessages())
                .flushBeforeCompact(c.isFlushBeforeCompact())
                .offloadBeforeCompact(c.isOffloadBeforeCompact())
                .build();
    }

    // ========== 工具组激活 ==========

    /**
     * 应用工具组激活策略。
     *
     * <p>
     * 只操作通过 {@code createToolGroup()} 创建的已知应用层工具组，
     * 不影响底层框架内置的未分组工具（如 {@code memory_search}、{@code agent_spawn} 等）。
     * 底层框架内置工具始终可用，不受工具组开关管控。
     * </p>
     *
     * <p>
     * 如果 Agent 启用了 MetaTool，则跳过工具组激活（Agent 会自主管理工具）。
     * 否则，按 YAML 配置激活指定的工具组。未配置时默认启用 memory 组。
     * </p>
     *
     * @param toolkit 工具集
     * @param def     Agent 定义配置
     */
    private void applyToolGroupActivation(Toolkit toolkit, AgentDefinition def) {
        boolean metaTool = def.getRuntime() != null && def.getRuntime().isEnableMetaTool();

        // MetaTool 模式：Agent 仍可经 reset_equipped_tools 自行管理工具组；
        // 但 MCP / 集成工具组必须在构建期预激活，否则 fresh 会话派生自 build 期快照的
        // initialActiveToolGroups 不含这些组，调用期会被 setActiveGroups(...) 覆盖为反激活，
        // 报 "Unauthorized ... not available"。故不再跳过激活。
        if (metaTool) {
            log.debug("MetaTool 模式: 仍预激活 MCP/集成工具组，Agent 可经 meta 工具后续重置");
        }

        // 收集 YAML 配置中指定的活跃工具组
        List<String> activeGroups = new ArrayList<>();
        ToolsGroupConfig tgc = def.getToolsGroup();
        if (tgc != null) {
            if (tgc.getSystemToolsGroup() != null)
                activeGroups.addAll(tgc.getSystemToolsGroup());
            if (tgc.getMcpServersToolsGroup() != null)
                activeGroups.addAll(tgc.getMcpServersToolsGroup());
        }
        // tools.mcpServers 同样隐含“加载服务器 + 激活对应组”，需并入激活列表
        if (def.getTools() != null && def.getTools().getMcpServers() != null) {
            activeGroups.addAll(def.getTools().getMcpServers());
        }
        // 未配置时默认启用 memory 组
        if (activeGroups.isEmpty())
            activeGroups.add("memory");

        // 先禁用所有已知组，再激活配置中指定的组
        // 只操作通过 createToolGroup() 创建的应用层组，不影响框架内置的未分组工具
        List<String> knownGroups = List.of("agent", "memory", "filesystem", "execute", "page", "general");
        for (String group : knownGroups) {
            if (toolkit.getToolGroup(group) != null) {
                toolkit.updateToolGroups(List.of(group), false);
            }
        }
        for (String group : activeGroups) {
            if (toolkit.getToolGroup(group) != null) {
                toolkit.updateToolGroups(List.of(group), true);
            } else {
                log.debug("工具组 '{}' 不存在，跳过激活", group);
            }
        }
        // 捕获构建期意图激活组，供会话级钩子 activateSessionToolGroups 使用，
        // 以覆盖持久化/遗留空激活组导致的 MCP 工具 "Unauthorized ... is not available"。
        AGENT_ACTIVE_GROUPS.put(def.getName(), new ArrayList<>(activeGroups));
        log.info("[toolgroup-activate] 构建期激活工具组 agent={} groups={}", def.getName(), activeGroups);
    }

    /**
     * 会话级工具组激活（根治点）。
     *
     * <p>GA 在每次调用期（ReActAgent.activateSlotForContext）会以
     * {@code toolkit.setActiveGroups(loaded.getToolContext().getActivatedGroups())}
     * 把会话状态的激活组覆盖到 toolkit 上。若会话状态来自持久化存储（或 v1 遗留空列表），
     * 其激活组为空，MCP 工具组会被反激活，运行时报
     * {@code Unauthorized tool call: '<tool>' is not available}。</p>
     *
     * <p>本方法在每个会话调用前，把该会话状态的激活组重置为构建期已激活的工具组
     * （含 systemToolsGroup + mcpServersToolsGroup + 动态注册组），从而覆盖持久化空状态。
     * 由于 {@code getAgentState(userId, sessionId)} 走 stateCache.computeIfAbsent，
     * 此处写入的状态会被调用期直接复用，setActiveGroups 将激活正确的 MCP 工具组。</p>
     *
     * @param agent     HarnessAgent 实例
     * @param userId    用户标识
     * @param sessionId 会话标识
     */
    public static void activateSessionToolGroups(HarnessAgent agent, String userId, String sessionId) {
        if (agent == null || userId == null || sessionId == null) {
            return;
        }
        try {
            List<String> groups = resolveBuildTimeGroups(agent.getName());
            if (groups == null) {
                log.warn("未找到 Agent '{}' 的构建期工具组快照，跳过会话级激活", agent.getName());
                return;
            }
            RuntimeContext rc = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
            AgentState state = agent.getDelegate().getAgentState(rc);
            state.getToolContext().setActivatedGroups(groups);
            // 关键：stateStore != null 时 GA 每次调用都会从存储重新加载状态并覆盖本地缓存，
            // 因此必须把修正后的激活组写回存储，否则 activateSlotForContext 重新加载时又会丢失。
            AgentStateStore store = agent.getStateStore();
            if (store != null) {
                store.save(userId, sessionId, "agent_state", state);
            }
            log.debug("[toolgroup-activate] agent={} user={} session={} saved activatedGroups={}",
                    agent.getName(), userId, sessionId, groups);
        } catch (Exception e) {
            log.warn("会话级工具组激活失败 (agent={}, user={}): {}", agent.getName(), userId, e.getMessage());
        }
    }

    /**
     * 解析运行时 Agent 名称对应的构建期激活工具组快照。
     *
     * <p>构建期快照以「基础 Agent 名」为键写入，而运行时被路由命中的可能是
     * Profile 实例（组合键 {@code agentName#profileName}，与基础实例共享同一
     * Toolkit）。因此先按精确名查找，未命中时回退到 {@code #} 之前的基础名，
     * 避免 Profile 实例因取不到快照而跳过会话级激活，导致 MCP 工具组被
     * 调用期的 setActiveGroups 反激活（报 "Unauthorized ... is not available"）。</p>
     *
     * <p>返回结果并入运行时动态注册的 MCP 工具组，使动态注册的服务器在
     * 会话级激活后仍保持可见。</p>
     *
     * @param agentName 运行时 Agent 名称（基础名或 {@code 基础名#profile}）
     * @return 应激活的工具组列表；无快照时返回 null
     */
    private static List<String> resolveBuildTimeGroups(String agentName) {
        if (agentName == null) {
            return null;
        }
        List<String> groups = AGENT_ACTIVE_GROUPS.get(agentName);
        if (groups == null) {
            int idx = agentName.indexOf('#');
            if (idx > 0) {
                groups = AGENT_ACTIVE_GROUPS.get(agentName.substring(0, idx));
            }
        }
        if (groups == null) {
            return null;
        }
        if (DYNAMIC_ACTIVE_GROUPS.isEmpty()) {
            return groups;
        }
        List<String> merged = new ArrayList<>(groups);
        for (String dynamic : DYNAMIC_ACTIVE_GROUPS) {
            if (!merged.contains(dynamic)) {
                merged.add(dynamic);
            }
        }
        return merged;
    }

    // ========== MCP 动态注册（Nacos 协调底座）==========

    /**
     * 运行时动态注册一个 MCP 服务器到本 JVM 所有已构建 Agent。
     * 复用 mcpClientCache + buildMcpClient/wrapWithReconnect，注入工具组并激活。
     *
     * @param name 服务器名称（即工具组名）
     * @param src  服务器配置（type/url/command/args/headers/env/timeout）
     */
    public void registerDynamicMcpServer(String name, AgentscopeCoreProperties.McpServerConfig src) {
        if (src == null) {
            throw new IllegalArgumentException("MCP 服务器配置不能为空: " + name);
        }
        src.setEnabled(true);
        // 记入动态组集合，使会话级激活（activateSessionToolGroups）不会把该组反激活
        DYNAMIC_ACTIVE_GROUPS.add(name);
        McpClientWrapper wrapper = mcpClientCache.computeIfAbsent(name, n -> wrapWithReconnect(n, src));
        // 同步：先创建并激活工具组（createToolGroup/updateToolGroups 不触发 MCP 连接），工具立即可见，
        // HTTP 请求立即返回，不阻塞在 initialize 的 30s 超时上。
        int injected = 0;
        for (Toolkit tk : registeredToolkits) {
            try {
                try {
                    tk.createToolGroup(name, "MCP 服务器: " + name, false);
                } catch (Exception ignored) {
                    // 工具组已存在，忽略
                }
                tk.updateToolGroups(List.of(name), true);
                injected++;
            } catch (Exception e) {
                log.warn("[MCP] 动态注册 '{}' 创建工具组失败：{}", name, e.getMessage());
            }
        }
        log.info("动态注册 MCP 服务器 {} 已受理，已创建 {} 个工具组（连接在后台建立，首次调用时若不可达将自动重连）",
                name, injected);
        // 异步：在后台线程注入 MCP 客户端并激活连接。apply() 会触发 initialize，目标不可达时阻塞约 30s，
        // 放到后台不阻塞 HTTP 请求；连接失败由 ReconnectingMcpClientWrapper 在首次调用时自动重连。
        final McpClientWrapper fw = wrapper;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            for (Toolkit tk : registeredToolkits) {
                try {
                    tk.registration().mcpClient(fw).group(name).apply();
                    log.info("[MCP] 动态注册 '{}' 连接已建立，工具已加载进工具组", name);
                } catch (Exception e) {
                    log.warn("[MCP] 动态注册 '{}' 连接失败（首次调用时将自动重连）：{}", name, e.getMessage());
                }
            }
        });
    }

    /**
     * 运行时注销 MCP 服务器：禁用工具组并关闭连接。
     *
     * @param name 服务器名称
     */
    public void unregisterDynamicMcpServer(String name) {
        DYNAMIC_ACTIVE_GROUPS.remove(name);
        for (Toolkit tk : registeredToolkits) {
            try {
                tk.updateToolGroups(List.of(name), false);  // 先停用工具组
                tk.removeToolGroups(List.of(name));          // 再真正移除工具组
            } catch (Exception ignored) {
                // 工具组不存在或已禁用，忽略
            }
        }
        McpClientWrapper wrapper = mcpClientCache.remove(name);
        if (wrapper != null) {
            try {
                wrapper.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 客户端 {} 失败", name, e);
            }
        }
        log.info("注销 MCP 服务器 {}", name);
    }

    /**
     * 根据 Nacos 目录远端状态，对本 JVM 做增量 reconcile（跨实例同步入口）。
     * 远端有而本地无 → 注册；本地有而远端无/禁用 → 注销。
     */
    private void reconcileMcpServers(Map<String, AgentscopeCoreProperties.McpServerConfig> remote) {
        if (remote == null) {
            return;
        }
        for (Map.Entry<String, AgentscopeCoreProperties.McpServerConfig> e : remote.entrySet()) {
            if (e.getValue() != null && e.getValue().isEnabled() && !mcpClientCache.containsKey(e.getKey())) {
                try {
                    // 复用 resilience4j circuitbreaker 保护 reconcile 注册动作
                    mcpReconcileCircuitBreaker.executeRunnable(
                            () -> registerDynamicMcpServer(e.getKey(), e.getValue()));
                } catch (Exception ex) {
                    log.error("reconcile 注册 MCP 服务器 {} 失败", e.getKey(), ex);
                }
            }
        }
        for (String name : new java.util.HashSet<>(mcpClientCache.keySet())) {
            AgentscopeCoreProperties.McpServerConfig cfg = remote.get(name);
            if (cfg == null || !cfg.isEnabled()) {
                try {
                    mcpReconcileCircuitBreaker.executeRunnable(() -> unregisterDynamicMcpServer(name));
                } catch (Exception ex) {
                    log.error("reconcile 注销 MCP 服务器 {} 失败", name, ex);
                }
            }
        }
    }

    /**
     * MCP 协调底座引导（在 Agent 构建完成后由 start() 调用一次）：
     * 1. 初始化 Nacos 客户端并监听目录变化
     * 2. 把 YAML 已启用的 MCP 服务器同步到 Nacos 目录（首次对齐）
     * 3. 注册协调实例（跨 JVM 广播感知）
     */
    private void bootstrapMcpCoordinator() {
        if (mcpConfigStore == null || !mcpConfigStore.isEnabled()) {
            return;
        }
        try {
            mcpConfigStore.init();
            Map<String, AgentscopeCoreProperties.McpServerConfig> yamlServers = coreProperties.getMcpServers();
            Map<String, AgentscopeCoreProperties.McpServerConfig> enabled = new java.util.HashMap<>();
            if (yamlServers != null) {
                for (Map.Entry<String, AgentscopeCoreProperties.McpServerConfig> e : yamlServers.entrySet()) {
                    if (e.getValue() != null && e.getValue().isEnabled()) {
                        enabled.put(e.getKey(), e.getValue());
                    }
                }
            }
            // 一次性合并发布，避免逐条 saveServer 的读-改-写竞态（Nacos 最终一致，先发的条目会被后发的旧值覆盖）
            log.info("[MCP] 引导：YAML 启用服务器共 {} 个，开始一次性同步到 Nacos 目录 {}", enabled.size(), enabled.keySet());
            mcpConfigStore.syncServers(enabled);
            // 把 YAML 启用服务器注册为 Naming 实例（此时 namingService 已 init）
            for (Map.Entry<String, AgentscopeCoreProperties.McpServerConfig> e : enabled.entrySet()) {
                mcpConfigStore.registerServerInstance(e.getKey(), e.getValue());
            }
            mcpConfigStore.addServerChangeListener(this::reconcileMcpServers);
            String ip = InetAddress.getLocalHost().getHostAddress();
            mcpConfigStore.setLocalAddress(ip, serverPort);  // 供 Naming 实例注册使用
            mcpConfigStore.registerCoordinatorInstance(ip, serverPort);
            log.info("MCP 协调底座引导完成，本实例 {}:{}", ip, serverPort);
        } catch (Exception e) {
            log.error("MCP 协调底座引导失败（不影响 Agent 启动）", e);
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 查找 Agent 自定义扩展 SPI 实现。
     *
     * <p>
     * 从 Agent 定义的 extensions.agentCustomizer 配置中获取 Bean 名称，
     * 再从 Spring 容器中查找对应的 AgentCustomizer 实现。
     * </p>
     *
     * @param def Agent 定义配置
     * @return AgentCustomizer 实例，未配置时返回 null
     */
    private AgentCustomizer findCustomizer(AgentDefinition def) {
        if (def.getExtensions() == null || customizerProvider == null)
            return null;
        String beanName = def.getExtensions().getAgentCustomizer();
        if (beanName == null || beanName.isBlank())
            return null;
        return customizerProvider.getIfAvailable();
    }

    /**
     * 获取 Agent 的描述信息。
     *
     * <p>
     * 优先使用 displayName，其次使用 description，最后使用 name。
     * </p>
     *
     * @param def Agent 定义配置
     * @return Agent 描述信息
     */
    private String description(AgentDefinition def) {
        if (def.getDisplayName() != null)
            return def.getDisplayName();
        if (def.getDescription() != null)
            return def.getDescription();
        return def.getName();
    }

}
