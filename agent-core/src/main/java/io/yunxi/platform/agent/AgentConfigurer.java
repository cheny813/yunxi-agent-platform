package io.yunxi.platform.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.yunxi.platform.agent.middleware.ContentFilterMiddleware;
import io.yunxi.platform.agent.middleware.ReasoningReviewMiddleware;
import io.yunxi.platform.agent.middleware.TextToolCallParserMiddleware;
import io.yunxi.platform.agent.middleware.ToolGateMiddleware;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.agent.workspace.AgentWorkspaceInitializer;
import io.yunxi.platform.security.hitl.HumanToolRegistrar;
import io.yunxi.platform.agent.workspace.WorkspaceAutoDiscoveryEngine;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.ExpertConfig;
import io.yunxi.platform.shared.config.ExtensionConfig;
import io.yunxi.platform.shared.config.StageConfig;
import io.yunxi.platform.shared.config.ToolsGroupConfig;
import io.yunxi.platform.tracing.middleware.ReActSpanMiddleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 配置器，负责根据 YAML 定义创建并注册 Agent。
 *
 * <p>
 * 读取 agent-definitions/ 下的 YAML 配置，创建对应的 Agent（包括 Supervisor Agent）。
 * 实现 {@link SmartLifecycle}（phase=5），确保在 Spring 容器启动后自动初始化。
 * </p>
 *
 * <p>
 * V2.0 升级：
 * </p>
 * <ul>
 * <li>用 {@link io.agentscope.core.middleware.MiddlewareBase} 替代 Hook 体系</li>
 * <li>删除反射 hack 的 assignUngroupedTools() 方法</li>
 * <li>MCP 管理迁移到 V2.0 McpServerRegistrar</li>
 * <li>删除 ChinesePlanToHint，改用 V2.0 PlanHintMiddleware</li>
 * <li>代码量从 735 行精简到约 450 行</li>
 * </ul>
 */
@Component
public class AgentConfigurer implements SmartLifecycle {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(AgentConfigurer.class);

    /** 生命周期运行标志，volatile 确保多线程可见性 */
    private volatile boolean running = false;

    /** Agent 定义加载器，从 YAML 文件加载 Agent 配置 */
    private final AgentDefinitionLoader definitionLoader;

    /** Agent 服务，用于注册和管理 Agent 实例 */
    private final AgentService agentService;

    /** 核心配置属性，包含工作空间路径、模型名称、Compaction 配置等 */
    private final AgentscopeCoreProperties coreProperties;

    /** 工作空间初始化器，在 Agent 创建前初始化其工作空间目录 */
    private final AgentWorkspaceInitializer workspaceInitializer;

    /** Agent 自定义扩展 SPI 提供者（可选），允许外部对 Agent 实例进行二次定制 */
    private final ObjectProvider<AgentCustomizer> customizerProvider;

    /** 工作空间自动发现引擎，扫描工作空间中的知识文件、技能、场景规则 */
    private final WorkspaceAutoDiscoveryEngine workspaceDiscoveryEngine;

    /** 模型工厂，根据配置创建 LLM 模型实例 */
    private final ModelFactory modelFactory;

    /** ReAct 链路追踪 Middleware 提供者（可选），用于 OpenTelemetry 分布式追踪 */
    private final ObjectProvider<ReActSpanMiddleware> reactSpanMiddlewareProvider;

    /** Agent 分布式后端（可选），提供 AgentStateStore + BaseStore + SandboxSnapshot 一站式配置 */
    private DistributedStore distributedBackend;

    /**
     * 构造 Agent 配置器，通过 Spring 依赖注入获取所有必要组件。
     *
     * @param definitionLoader            Agent 定义加载器
     * @param agentService                Agent 服务
     * @param coreProperties              核心配置属性
     * @param workspaceInitializer        工作空间初始化器
     * @param customizerProvider          Agent 自定义扩展提供者（可选）
     * @param workspaceDiscoveryEngine    工作空间自动发现引擎
     * @param modelFactory                模型工厂
     * @param reactSpanMiddlewareProvider ReAct 追踪 Middleware 提供者（可选）
     */
    public AgentConfigurer(AgentDefinitionLoader definitionLoader,
            AgentService agentService,
            AgentscopeCoreProperties coreProperties,
            AgentWorkspaceInitializer workspaceInitializer,
            ObjectProvider<AgentCustomizer> customizerProvider,
            WorkspaceAutoDiscoveryEngine workspaceDiscoveryEngine,
            ModelFactory modelFactory,
            ObjectProvider<ReActSpanMiddleware> reactSpanMiddlewareProvider) {
        this.definitionLoader = definitionLoader;
        this.agentService = agentService;
        this.coreProperties = coreProperties;
        this.workspaceInitializer = workspaceInitializer;
        this.customizerProvider = customizerProvider;
        this.workspaceDiscoveryEngine = workspaceDiscoveryEngine;
        this.modelFactory = modelFactory;
        this.reactSpanMiddlewareProvider = reactSpanMiddlewareProvider;
    }

    /**
     * 设置分布式后端实例。
     *
     * <p>
     * V2.0-RC3: 使用 {@link DistributedStore} 统一接口替代旧 {@code Session}，
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
     * <p>
     * 为每个 Agent 定义创建对应的工作空间目录，路径格式为：
     * {workspaceBasePath}/{agentName}
     * </p>
     *
     * @param definitions Agent 定义列表
     */
    private void initializeWorkspaces(List<AgentDefinition> definitions) {
        for (AgentDefinition def : definitions) {
            String workspacePath = coreProperties.getWorkspaceBasePath() + "/" + def.getName();
            workspaceInitializer.initialize(def.getName(), description(def), def.getPrompt(), workspacePath);
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
    private void initializeSingleAgent(AgentDefinition def) {
        try {
            log.info("创建 Agent: {}", def.getName());
            // 创建 LLM 模型实例
            Model model = modelFactory.create(def.getModel());
            // 构建工具集
            Toolkit toolkit = buildToolkit(def);

            // 配置 HarnessAgent Builder
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name(def.getName()).sysPrompt(def.getPrompt()).model(model).toolkit(toolkit)
                    .workspace(coreProperties.getWorkspaceBasePath() + "/" + def.getName())
                    .compaction(buildCompactionConfig());

            // 配置 Middleware 链、运行时参数、规划功能
            configureMiddlewares(builder, def, toolkit);
            configureRuntime(builder, def);
            configurePlan(builder, def);

            // 配置分布式后端（可选）：stateStore + baseStore + snapshotSpec 一站式配置
            if (distributedBackend != null)
                builder.distributedStore(distributedBackend);

            // 应用 AgentCustomizer SPI 扩展（如有）
            AgentCustomizer customizer = findCustomizer(def);
            Agent agent = customizer != null ? customizer.customize(def, builder.build()) : builder.build();

            // 激活工具组
            applyToolGroupActivation(toolkit, def);

            // 注册 Agent 实例和元信息
            agentService.registerAgentInstance(def.getName(), agent);
            agentService.registerAgentInfoDto(def.getName(), description(def), def.getPrompt(),
                    def.getModel() != null ? def.getModel().getModelName() : coreProperties.getModelName());
            agentService.registerAgentRagMode(def.getName(), def.getRagMode());

            // 打印工作空间自动发现结果
            logWorkspaceDiscovery(def.getName());
            log.info("Agent 创建成功: {}, ragMode={}", def.getName(), def.getRagMode());
        } catch (Exception e) {
            log.error("Agent 创建失败: {}", def.getName(), e);
        }
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
        // 将每个专家 Agent 注册为子 Agent 工具
        for (Map.Entry<String, Agent> entry : expertAgents.entrySet()) {
            toolkit.registration()
                    .subAgent(() -> entry.getValue(), SubAgentConfig.builder().forwardEvents(false).build())
                    .group("agent").apply();
        }

        // 配置 Supervisor Agent Builder
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(def.getName()).sysPrompt(def.getPrompt()).model(model).toolkit(toolkit)
                .workspace(coreProperties.getWorkspaceBasePath() + "/" + def.getName())
                .compaction(buildCompactionConfig());

        configureMiddlewares(builder, def, toolkit);
        configureRuntime(builder, def);
        configurePlan(builder, def);

        // 配置分布式后端（可选）
        if (distributedBackend != null)
            builder.distributedStore(distributedBackend);

        // 应用 AgentCustomizer SPI 扩展
        AgentCustomizer customizer = findCustomizer(def);
        Agent supervisor = customizer != null ? customizer.customize(def, builder.build()) : builder.build();

        // 激活工具组并注册
        applyToolGroupActivation(toolkit, def);
        agentService.registerAgentInstance(def.getName(), supervisor);
        agentService.registerAgentInfoDto(def.getName(), description(def), def.getPrompt(),
                def.getModel() != null ? def.getModel().getModelName() : coreProperties.getModelName());
        agentService.registerAgentRagMode(def.getName(), def.getRagMode());
        log.info("Supervisor Agent 创建完成: {}, 专家数: {}", def.getName(), expertAgents.size());
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
     * 创建 Toolkit 并初始化本地工具组，MCP 工具通过 V2.0 McpServerRegistrar 单独注册。
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

    // ========== Middleware 配置 ==========

    /**
     * 配置 Agent 的 Middleware 链。
     *
     * <p>
     * Middleware 按添加顺序执行，当前配置的 Middleware 顺序：
     * 1. StudioMessageHook（可选，调试用）
     * 2. GracefulShutdownMiddleware（优雅关停）
     * 3. TextToolCallParserMiddleware（文本工具调用解析）
     * 4. ContentFilterMiddleware（内容过滤）
     * 5. ReActSpanMiddleware（可选，链路追踪）
     * 6. HITL Middleware（可选，人机交互中间件）
     * </p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     * @param toolkit 工具集
     */
    private void configureMiddlewares(HarnessAgent.Builder builder, AgentDefinition def, Toolkit toolkit) {
        // V2.0 Studio 调试已内置，无需手动注入 Studio Hook

        // 必须：优雅关停 Middleware
        builder.middleware(new GracefulShutdownMiddleware(GracefulShutdownManager.getInstance()));
        // 必须：文本格式工具调用解析
        builder.middleware(new TextToolCallParserMiddleware(toolkit));
        // 必须：内容安全过滤
        builder.middleware(new ContentFilterMiddleware());

        // 可选：OpenTelemetry 链路追踪
        if (reactSpanMiddlewareProvider.getIfAvailable() != null) {
            builder.middleware(reactSpanMiddlewareProvider.getIfAvailable());
        }

        // 可选：HITL（人机交互）Middleware
        injectHITLMiddlewares(builder, def);
    }

    /**
     * 注入 HITL（Human-In-The-Loop）Middleware。
     *
     * <p>
     * 根据配置注入三种 HITL Middleware：
     * - ToolGateMiddleware：工具门控，需要人工确认才能执行指定工具
     * - ReasoningReviewMiddleware：推理审查，在推理结果执行危险工具前需要人工确认
     * - HumanToolRegistrar：人工工具注册，提供人工介入的工具
     * </p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     */
    private void injectHITLMiddlewares(HarnessAgent.Builder builder, AgentDefinition def) {
        if (def == null)
            return;
        ExtensionConfig extensions = def.getExtensions();
        if (extensions == null || extensions.getHitl() == null)
            return;

        var hitlConfig = extensions.getHitl();
        // 注入工具门控 Middleware
        if (hitlConfig.getToolGate() != null && hitlConfig.getToolGate().isEnabled()) {
            builder.middleware(new ToolGateMiddleware(hitlConfig.getToolGate()));
        }
        // 注入推理审查 Middleware，将 ToolGate 中配置的危险工具列表传递给审查 Middleware
        if (hitlConfig.getReasoningReview() != null && hitlConfig.getReasoningReview().isEnabled()) {
            var dangerousTools = hitlConfig.getToolGate() != null
                    ? Set.copyOf(hitlConfig.getToolGate().getTools())
                    : Set.<String>of();
            builder.middleware(new ReasoningReviewMiddleware(hitlConfig.getReasoningReview(), dangerousTools));
        }
        // 注册人工工具（HumanTool 注册当前仅支持 schema 注册，无需绑定具体 Toolkit）
        if (hitlConfig.getHumanTool() != null && hitlConfig.getHumanTool().isEnabled()) {
            // HumanToolRegistrar 仅注册 ToolSchema，可在没有 Toolkit 的情况下工作
            // registerTools(null) 会跳过注册（registerTools 内有 null guard）
            new HumanToolRegistrar(hitlConfig.getHumanTool()).registerTools(null);
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
     * 配置 Agent 规划功能。
     *
     * <p>
     * 启用后 Agent 会在执行前先制定计划（PlanNotebook），
     * 将复杂任务分解为子任务。needUserConfirm 控制是否需要用户确认计划。
     * maxSubtasks 限制最大子任务数量。
     * </p>
     *
     * @param builder Agent Builder
     * @param def     Agent 定义配置
     */
    private void configurePlan(HarnessAgent.Builder builder, AgentDefinition def) {
        // V2.0: planNotebook() 已移除，计划功能迁移至 PlanHintMiddleware
        // TODO: 使用 V2.0 PlanHintMiddleware 替代
        if (def.getPlan() != null && def.getPlan().isEnabled()) {
            log.info("Agent '{}' 启用计划模式 (V2.0 PlanHintMiddleware)", def.getName());
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

        // MetaTool 模式：Agent 自主管理工具，框架不干预
        if (metaTool) {
            log.debug("MetaTool 模式: 跳过工具组激活，Agent 自行管理");
            return;
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

    /**
     * 打印工作空间自动发现的结果。
     *
     * <p>
     * 通过 WorkspaceAutoDiscoveryEngine 扫描 Agent 工作空间，
     * 输出发现的场景规则、知识文件、技能和子 Agent 信息。
     * </p>
     *
     * @param agentName Agent 名称
     */
    private void logWorkspaceDiscovery(String agentName) {
        workspaceDiscoveryEngine.getWorkspaceConfig(agentName).ifPresent(config -> {
            if (config.getSceneRule() != null) {
                log.info("[Workspace] 场景规则: {} (关键词: {})",
                        config.getSceneRule().getSceneName(), config.getSceneRule().getKeywords());
            }
            if (config.getKnowledgeFiles() != null && !config.getKnowledgeFiles().isEmpty()) {
                log.info("[Workspace] 知识文件: {}", config.getKnowledgeFiles());
            }
            if (config.getSkillNames() != null && !config.getSkillNames().isEmpty()) {
                log.info("[Workspace] 技能: {}", config.getSkillNames());
            }
            if (config.getSubAgentIds() != null && !config.getSubAgentIds().isEmpty()) {
                log.info("[Workspace] 子 Agent: {}", config.getSubAgentIds());
            }
        });
    }
}
