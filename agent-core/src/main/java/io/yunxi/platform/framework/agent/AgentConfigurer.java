package io.yunxi.platform.framework.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.session.Session;
import io.agentscope.core.shutdown.GracefulShutdownHook;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.yunxi.platform.framework.agent.extension.AgentCustomizer;
import io.yunxi.platform.framework.embedding.BaiduModelProvider;
import io.yunxi.platform.framework.embedding.ChatModelProvider;
import io.yunxi.platform.framework.embedding.ClaudeModelProvider;
import io.yunxi.platform.framework.embedding.DashScopeModelProvider;
import io.yunxi.platform.framework.embedding.HuaweiModelProvider;
import io.yunxi.platform.framework.embedding.OpenAIModelProvider;
import io.yunxi.platform.framework.hitl.HumanToolRegistrar;
import io.yunxi.platform.framework.hitl.ReasoningReviewHook;
import io.yunxi.platform.framework.hitl.ToolGateHook;
import io.yunxi.platform.framework.hook.TextToolCallParserHook;
import io.yunxi.platform.framework.mcp.McpToolRegistry;
import io.yunxi.platform.framework.observability.ReActSpanHook;
import io.yunxi.platform.framework.workspace.WorkspaceAutoDiscoveryEngine;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.ExpertConfig;
import io.yunxi.platform.shared.config.ExtensionConfig;
import io.yunxi.platform.shared.config.StageConfig;
import io.yunxi.platform.shared.config.ToolsGroupConfig;

/**
 * Agent 自动装配引擎 — 配置驱动的 Agent 初始化
 * <p>
 * 应用启动时执行两轮初始化：
 * <ol>
 * <li>第一轮：初始化所有独立 Agent，包含 MCP 工具注册/本地工具/SkillBox 等</li>
 * <li>第二轮：根据 orchestration 配置创建编排 Agent（Supervisor 等）</li>
 * </ol>
 * 实现了 SmartLifecycle（phase=5），在基础设施（Model/Toolkit/Memory/Session phase=0~4）
 * 就绪后才开始初始化 Agent。以前用 @EventListener(ApplicationReadyEvent) 没有顺序控制。
 * </p>
 * <p>
 * 工具注册不再经过 ToolRegistry + ToolAdapter 两层桥接，改为直接使用框架 Toolkit API。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AgentConfigurer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigurer.class);

    private volatile boolean running = false;

    /** Agent 定义加载器 — 读取 agent-definitions/ 目录下的 YAML 配置 */
    private final AgentDefinitionLoader definitionLoader;

    /** Agent 领域服务 — 注册 Agent 实例和信息 */
    private final AgentDomainService agentDomainService;

    /** AgentScope 核心配置属性（API Key、模型名称、默认提示词等） */
    private final AgentscopeCoreProperties coreProperties;

    /** MCP 工具注册表 — 加载和注册 MCP 服务器工具 */
    private final McpToolRegistry mcpToolRegistry;

    /** Agent 工作区初始化器 — 创建 AGENTS.md、knowledge/ 等目录结构 */
    private final AgentWorkspaceInitializer workspaceInitializer;

    /** Studio 消息 Hook 提供者（可选） */
    private final ObjectProvider<StudioMessageHook> studioMessageHookProvider;

    /** Agent 自定义装配器（可选）— 构建后处理 */
    private final ObjectProvider<AgentCustomizer> customizerProvider;

    /** 工作区自动发现引擎 */
    private final WorkspaceAutoDiscoveryEngine workspaceDiscoveryEngine;

    /** ReAct 追踪 Hook（可选） */
    private final ObjectProvider<ReActSpanHook> reActSpanHookProvider;

    /** 跨实例 Session（可选），由 AgentSessionConfig 按配置创建 */
    private Session session;

    public AgentConfigurer(AgentDefinitionLoader definitionLoader,
            AgentDomainService agentDomainService,
            AgentscopeCoreProperties coreProperties,
            McpToolRegistry mcpToolRegistry,
            AgentWorkspaceInitializer workspaceInitializer,
            ObjectProvider<StudioMessageHook> studioMessageHookProvider,
            ObjectProvider<AgentCustomizer> customizerProvider,
            WorkspaceAutoDiscoveryEngine workspaceDiscoveryEngine,
            ObjectProvider<ReActSpanHook> reActSpanHookProvider) {
        this.definitionLoader = definitionLoader;
        this.agentDomainService = agentDomainService;
        this.coreProperties = coreProperties;
        this.mcpToolRegistry = mcpToolRegistry;
        this.workspaceInitializer = workspaceInitializer;
        this.studioMessageHookProvider = studioMessageHookProvider;
        this.customizerProvider = customizerProvider;
        this.workspaceDiscoveryEngine = workspaceDiscoveryEngine;
        this.reActSpanHookProvider = reActSpanHookProvider;
    }

    @Autowired(required = false)
    public void setSession(Session session) {
        this.session = session;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        log.info("AgentConfigurer: 开始自动装配 Agent... (SmartLifecycle phase=5)");
        List<AgentDefinition> definitions = definitionLoader.getAgentDefinitions();
        if (definitions.isEmpty()) {
            log.warn("没有找到 Agent 配置（agent-definitions/ 目录为空）");
            running = true;
            return;
        }

        // 第 0 步：初始化所有 Agent 工作区目录结构
        initializeWorkspaces(definitions);

        // 第一轮：初始化所有独立 Agent
        for (AgentDefinition def : definitions) {
            if (!isOrchestrated(def))
                initializeSingleAgent(def);
        }

        // 第二轮：根据 orchestration 配置创建编排 Agent
        for (AgentDefinition def : definitions) {
            if (isOrchestrated(def))
                createOrchestratedAgent(def);
        }

        log.info("AgentConfigurer: Agent 自动装配完成，共 {} 个 Agent", agentDomainService.countAgents());
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        log.info("AgentConfigurer: 停止...");
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 5; // 在 Model(0)/Toolkit(1)/Memory(2)/Session(3)/Agent(4) 之后启动
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /**
     * 为所有 Agent 初始化工作区目录结构（AGENTS.md、knowledge/ 等）
     */
    private void initializeWorkspaces(List<AgentDefinition> definitions) {
        for (AgentDefinition def : definitions) {
            String workspacePath = coreProperties.getWorkspaceBasePath() + "/" + def.getName();
            workspaceInitializer.initialize(
                    def.getName(),
                    description(def),
                    def.getPrompt(),
                    workspacePath);
        }
    }

    private boolean isOrchestrated(AgentDefinition def) {
        if (def.getOrchestration() == null)
            return false;
        return !"single".equals(def.getOrchestration().getPattern());
    }

    // ========== 第一轮：初始化独立 Agent ==========

    private void initializeSingleAgent(AgentDefinition def) {
        try {
            log.info("初始化 Agent: {}", def.getName());
            ChatModelProvider modelProvider = createModelProvider(def);
            String prompt = def.getPrompt();

            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name(def.getName()).sysPrompt(prompt).model(modelProvider);

            // 创建 Toolkit 并注册所有工具
            Toolkit toolkit = new Toolkit();
            registerLocalTools(toolkit);
            registerMcpTools(toolkit, def);
            builder.toolkit(toolkit);

            // 配置通用 Builder 参数（workspace、compaction、hooks、runtime、plan）
            configureBuilder(builder, def, toolkit);

            // 扩展点
            AgentCustomizer customizer = findCustomizer(def);
            Agent agent = customizer != null ? customizer.customize(def, builder.build()) : builder.build();

            // 修正未分组工具 → 分配到 "general" 组
            Toolkit agentToolkit = resolveAgentToolkit(agent);
            assignUngroupedTools(agentToolkit, "general");

            // 工具组激活策略（在 Agent 内部 Toolkit 上执行）
            applyToolGroupActivation(agentToolkit != null ? agentToolkit : toolkit, def);

            // 注册
            agentDomainService.registerAgentInstance(def.getName(), agent);
            agentDomainService.registerAgentInfoDto(def.getName(), description(def), prompt,
                    def.getModel() != null ? def.getModel().getModelName() : coreProperties.getModelName());
            agentDomainService.registerAgentRagMode(def.getName(), def.getRagMode());

            // 记录工作区自动发现结果
            logWorkspaceDiscovery(def.getName());
            log.info("Agent 初始化成功: {}, ragMode={}", def.getName(), def.getRagMode());
        } catch (Exception e) {
            log.error("Agent 初始化失败: {}", def.getName(), e);
        }
    }

    // ========== 第二轮：创建编排 Agent ==========

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

    private void createSupervisorAgent(AgentDefinition def) {
        List<ExpertConfig> experts = def.getOrchestration().getExperts();
        if (experts == null || experts.isEmpty()) {
            log.warn("Supervisor [{}] 未配置专家，跳过", def.getName());
            return;
        }
        Map<String, Agent> expertAgents = new HashMap<>();
        for (ExpertConfig expert : experts) {
            try {
                Agent agent = agentDomainService.getAgentInstance(expert.getName());
                expertAgents.put(expert.getName(), agent);
            } catch (Exception e) {
                log.warn("专家 Agent 未找到: {} (Supervisor: {})", expert.getName(), def.getName());
            }
        }
        if (expertAgents.isEmpty()) {
            log.warn("Supervisor [{}] 没有可用的专家 Agent", def.getName());
            return;
        }

        ChatModelProvider modelProvider = createModelProvider(def);

        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("agent", "子Agent调用工具", true);
        for (Map.Entry<String, Agent> entry : expertAgents.entrySet()) {
            toolkit.registration()
                    .subAgent(() -> entry.getValue(), SubAgentConfig.builder().forwardEvents(false).build())
                    .group("agent").apply();
        }
        registerMcpTools(toolkit, def);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(def.getName()).sysPrompt(def.getPrompt()).model(modelProvider).toolkit(toolkit);

        // 配置通用 Builder 参数（workspace、compaction、hooks、runtime、plan）
        configureBuilder(builder, def, toolkit);

        AgentCustomizer customizer = findCustomizer(def);
        Agent supervisor = customizer != null ? customizer.customize(def, builder.build()) : builder.build();

        // 修正未分组工具 → 分配到 "general" 组
        Toolkit agentToolkit = resolveAgentToolkit(supervisor);
        assignUngroupedTools(agentToolkit, "general");

        // 工具组激活策略（在 Agent 内部 Toolkit 上执行）
        applyToolGroupActivation(agentToolkit != null ? agentToolkit : toolkit, def);
        agentDomainService.registerAgentInstance(def.getName(), supervisor);
        agentDomainService.registerAgentInfoDto(def.getName(), description(def), def.getPrompt(),
                def.getModel() != null ? def.getModel().getModelName() : coreProperties.getModelName());
        agentDomainService.registerAgentRagMode(def.getName(), def.getRagMode());
        log.info("Supervisor Agent 创建成功: {}, 专家数量: {}", def.getName(), expertAgents.size());
    }

    // ========== Pipeline / Routing ==========

    private void createPipelineAgent(AgentDefinition def) {
        List<StageConfig> stages = def.getOrchestration().getStages();
        if (stages == null || stages.isEmpty())
            return;
        for (StageConfig s : stages) {
            try {
                agentDomainService.getAgentInstance(s.getAgent());
            } catch (Exception e) {
                log.warn("Pipeline [{}] 阶段 Agent 未找到: {}", def.getName(), s.getAgent());
            }
        }
        log.info("Pipeline [{}] 配置验证通过，{} 个阶段", def.getName(), stages.size());
    }

    private void createRoutingAgent(AgentDefinition def) {
        List<ExpertConfig> experts = def.getOrchestration().getExperts();
        if (experts == null || experts.isEmpty())
            return;
        log.info("Routing [{}] 配置验证通过，{} 个子 Agent", def.getName(), experts.size());
    }

    // ========== 工具注册 ==========

    /**
     * 注册标记了 @Tool 注解的工具到 Toolkit
     * <p>
     * 工具通过框架 @Tool 注解自动声明，此处注入到每个 Agent 的 Toolkit 实例中。
     * 由 Starter 自动扫描 @Tool 标记的 Spring Bean。
     * </p>
     */
    private void registerLocalTools(Toolkit toolkit) {
        // @Tool 注解的工具由框架 Toolkit.registerTool(Object) 自动注册
        // 此处作为扩展点，如需手动注册 AgentTool 实现可在此添加
        createLocalToolGroups(toolkit);
    }

    private void registerMcpTools(Toolkit toolkit, AgentDefinition def) {
        ToolsGroupConfig tgc = def.getToolsGroup();
        if (tgc == null || tgc.getMcpServersToolsGroup() == null)
            return;
        List<String> servers = tgc.getMcpServersToolsGroup();
        mcpToolRegistry.registerAgentToolkit(def.getName(), toolkit);
        mcpToolRegistry.loadAndRegisterMcpTools(def.getName(), servers);
    }

    /**
     * 创建本地工具组。
     *
     * <p>工具组按职责隔离，每个 Agent 通过 YAML 的 {@code toolsGroup} 指定可见的组。
     * 未配置 {@code tools} 时默认仅激活 {@code memory} 组。
     *
     * <p>工具组分为两大类：
     *
     * <h3>一、系统内置组（代码定义，所有 Agent 都可配置）</h3>
     * <pre>
     * memory     → 记忆查询工具
     *              memory_search, memory_get
     *              session_history, session_search, session_list
     *
     * filesystem → 文件读写工具
     *              read_file, write_file, edit_file
     *              glob_files, list_files, grep_files
     *
     * execute    → 命令执行工具（高危）
     *              execute
     *
     * agent      → 子Agent调用工具
     *              call_agent, agent_send, agent_spawn
     *              task_list, task_cancel, task_output
     *
     * page       → 页面生成工具
     *              pagegen_xxx（以 "pagegen_" 开头的工具）
     *
     * general    → 其他未归类的兜底组
     * </pre>
     *
     * <h3>二、MCP 服务器组（由 MCP 服务器注册时动态创建，组名 = 服务器名）</h3>
     * <pre>
     * 例如 toolsGroup.mcpServersToolsGroup 配置了 database 和 redis：
     *   database  → database_query, database_execute（由 database MCP 服务器注册）
     *   redis     → redis_get, redis_set（由 redis MCP 服务器注册）
     *   formfill  → formfill_recipe（由 formfill MCP 服务器注册）
     * </pre>
     *
     * 配置示例：
     * <pre>{@code
     * # 只用系统内置组
     * tools:
     *   groups: [memory, filesystem]
     *
     * # 用 MCP 工具 + 内置组
     * tools:
     *   groups: [memory, database, redis]
     *   mcpServers: [database, redis]
     * }</pre>
     */
    private void createLocalToolGroups(Toolkit toolkit) {
        for (String[] g : new String[][] {
                { "agent", "子Agent调用工具" },
                { "memory", "记忆查询工具" },
                { "filesystem", "文件读写工具" },
                { "execute", "命令执行工具" },
                { "page", "页面生成工具" },
                { "general", "通用本地工具" } }) {
            try {
                toolkit.createToolGroup(g[0], g[1], true);
            } catch (Exception ignored) {
            }
        }
    }

    private String resolveLocalToolGroup(String toolName) {
        if (toolName.startsWith("call_"))
            return "agent";
        if (toolName.startsWith("pagegen_"))
            return "page";
        if (toolName.startsWith("memory_") || toolName.startsWith("session_"))
            return "memory";
        if (toolName.startsWith("execute"))
            return "execute";
        // 文件系统工具
        if (toolName.equals("read_file") || toolName.equals("write_file")
                || toolName.equals("edit_file") || toolName.equals("glob_files")
                || toolName.equals("list_files") || toolName.equals("grep_files"))
            return "filesystem";
        return "general";
    }

    // ========== 结构化输出 / Hook 注入 / 工具组激活 ==========

    /**
     * 从配置构建 CompactionConfig
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

    /**
     * 配置通用 Builder 参数
     * <p>
     * 包含 workspace 路径、compaction、hooks、runtime（maxIters/metaTool）、planNotebook。
     * 被 {@link #initializeSingleAgent} 和 {@link #createSupervisorAgent} 共用，
     * 消除重复的 HarnessAgent.Builder 配置代码。
     * </p>
     *
     * @param builder HarnessAgent Builder 实例（已设置 name、sysPrompt、model、toolkit）
     * @param def     Agent 定义配置
     * @param toolkit Agent 工具包
     */
    private void configureBuilder(HarnessAgent.Builder builder, AgentDefinition def, Toolkit toolkit) {
        builder.workspace(coreProperties.getWorkspaceBasePath() + "/" + def.getName())
                .compaction(buildCompactionConfig());

        // Session 持久化：默认用 WorkspaceSession（文件系统），配置 redis 时跨实例共享
        if (session != null) {
            builder.session(session);
        }

        injectStandardHooks(builder, toolkit);
        injectHITLHooks(builder, toolkit, def);

        if (def.getRuntime() != null) {
            builder.maxIters(def.getRuntime().getMaxIterations());
            if (def.getRuntime().isEnableMetaTool()) {
                builder.enableMetaTool(true);
            }
        }
        if (def.getPlan() != null && def.getPlan().isEnabled()) {
            PlanNotebook.Builder pb = PlanNotebook.builder()
                    .needUserConfirm(def.getPlan().isUserConfirm())
                    .planToHint(new ChinesePlanToHint());
            if (def.getPlan().getMaxSubtasks() != null) {
                pb.maxSubtasks(def.getPlan().getMaxSubtasks());
            }
            builder.planNotebook(pb.build());
        }
    }

    /**
     * 注入标准 Hook 集合（Studio + TextToolCallParser + GracefulShutdown +
     * SessionPersistence）
     * <p>
     * SessionPersistenceHook 由 HarnessAgent 自动注册，无需手动添加。
     * GracefulShutdownHook 在每轮 ReAct 后做 checkpoint，支持优雅关闭后恢复。
     * </p>
     */
    private void injectStandardHooks(HarnessAgent.Builder builder, Toolkit toolkit) {
        if (studioMessageHookProvider.getIfAvailable() != null) {
            builder.hook(studioMessageHookProvider.getIfAvailable());
        }
        builder.hook(new GracefulShutdownHook(GracefulShutdownManager.getInstance()));
        TextToolCallParserHook textToolCallParserHook = new TextToolCallParserHook(toolkit);
        builder.hook(textToolCallParserHook);
        // ReAct 追踪 Hook（可选，OTel 未启用时自动跳过）
        if (reActSpanHookProvider.getIfAvailable() != null) {
            builder.hook(reActSpanHookProvider.getIfAvailable());
        }
    }

    /**
     * 注入 HITL (Human-in-the-Loop) Hook
     * <p>
     * 根据 AgentDefinition YAML 中的 {@code extensions.hitl} 配置，按需注入：
     * ToolGateHook(55) → ReasoningReviewHook(70) + HumanTool(SchemaOnlyTool)
     * </p>
     */
    private void injectHITLHooks(HarnessAgent.Builder builder, Toolkit toolkit,
            AgentDefinition def) {
        if (def == null)
            return;
        ExtensionConfig extensions = def.getExtensions();
        if (extensions == null || extensions.getHitl() == null)
            return;
        var hitlConfig = extensions.getHitl();
        if (hitlConfig.getToolGate() != null && hitlConfig.getToolGate().isEnabled()) {
            builder.hook(new ToolGateHook(hitlConfig.getToolGate()));
        }
        if (hitlConfig.getReasoningReview() != null && hitlConfig.getReasoningReview().isEnabled()) {
            var dangerousTools = hitlConfig.getToolGate() != null
                    ? Set.copyOf(hitlConfig.getToolGate().getTools())
                    : Set.<String>of();
            builder.hook(new ReasoningReviewHook(hitlConfig.getReasoningReview(), dangerousTools));
        }
        if (hitlConfig.getHumanTool() != null && hitlConfig.getHumanTool().isEnabled()) {
            new HumanToolRegistrar(hitlConfig.getHumanTool()).registerTools(toolkit);
        }
    }

    private void applyToolGroupActivation(Toolkit toolkit, AgentDefinition def) {
        boolean metaTool = def.getRuntime() != null && def.getRuntime().isEnableMetaTool();
        Set<String> allGroups = collectAllGroupNames(toolkit);
        if (metaTool) {
            allGroups.forEach(g -> {
                try {
                    toolkit.updateToolGroups(List.of(g), false);
                } catch (Exception ignored) {
                }
            });
            return;
        }
        // 确定要激活的组：toolsGroup.systemToolsGroup + toolsGroup.mcpServersToolsGroup
        List<String> activeGroups = new ArrayList<>();
        ToolsGroupConfig tgc = def.getToolsGroup();
        if (tgc != null) {
            if (tgc.getSystemToolsGroup() != null) {
                activeGroups.addAll(tgc.getSystemToolsGroup());
            }
            if (tgc.getMcpServersToolsGroup() != null) {
                activeGroups.addAll(tgc.getMcpServersToolsGroup());
            }
        }
        // 如果都没配，默认仅 memory
        if (activeGroups.isEmpty()) {
            activeGroups.add("memory");
        }
        allGroups.forEach(g -> {
            try {
                toolkit.updateToolGroups(List.of(g), false);
            } catch (Exception ignored) {
            }
        });
        activeGroups.forEach(g -> {
            try {
                toolkit.updateToolGroups(List.of(g), true);
            } catch (Exception ignored) {
            }
        });
    }

    private Set<String> collectAllGroupNames(Toolkit toolkit) {
        Set<String> names = toolkit.getToolNames().stream()
                .filter(n -> !n.startsWith("create_plan") && !n.startsWith("finish_plan")
                        && !n.startsWith("update_") && !n.startsWith("view_")
                        && !n.startsWith("finish_subtask") && !n.startsWith("recover_")
                        && !n.startsWith("revise_") && !n.startsWith("load_skill")
                        && !n.equals("reset_equipped_tools"))
                .map(n -> {
                    int i = n.indexOf('_');
                    return i > 0 ? n.substring(0, i) : "general";
                })
                .collect(Collectors.toSet());
        names.addAll(List.of("agent", "page", "general"));
        return names;
    }

    // ========== 工具方法 ==========

    private ChatModelProvider createModelProvider(AgentDefinition def) {
        String provider = def.getModel() != null && def.getModel().getProvider() != null
                ? def.getModel().getProvider()
                : coreProperties.getProvider();
        String apiKey = def.getModel() != null && def.getModel().getApiKey() != null
                ? def.getModel().getApiKey()
                : coreProperties.getApiKey();
        String modelName = def.getModel() != null && def.getModel().getModelName() != null
                ? def.getModel().getModelName()
                : coreProperties.getModelName();

        ChatModelProvider modelProvider = switch (provider.toLowerCase()) {
            case "dashscope" -> {
                DashScopeModelProvider p = new DashScopeModelProvider(apiKey, modelName);
                if (def.getStructuredOutput() != null && def.getStructuredOutput().isEnabled()) {
                    p.setStructuredOutputSchema(def.getStructuredOutput().getSchema());
                }
                yield p;
            }
            case "openai" -> new OpenAIModelProvider(apiKey, modelName);
            case "claude" -> new ClaudeModelProvider(apiKey, modelName);
            case "baidu" -> new BaiduModelProvider(apiKey, modelName);
            case "huawei" -> new HuaweiModelProvider(apiKey, modelName);
            default -> throw new IllegalArgumentException("不支持的模型提供商: " + provider);
        };
        log.info("创建模型提供商: provider={}, model={}", provider, modelName);
        return modelProvider;
    }

    private AgentCustomizer findCustomizer(AgentDefinition def) {
        if (def.getExtensions() == null || customizerProvider == null)
            return null;
        String beanName = def.getExtensions().getAgentCustomizer();
        if (beanName == null || beanName.isBlank())
            return null;
        return customizerProvider.getIfAvailable();
    }

    private String description(AgentDefinition def) {
        if (def.getDisplayName() != null)
            return def.getDisplayName();
        if (def.getDescription() != null)
            return def.getDescription();
        return def.getName();
    }

    /**
     * 记录工作区自动发现结果
     */
    private void logWorkspaceDiscovery(String agentName) {
        workspaceDiscoveryEngine.getWorkspaceConfig(agentName).ifPresent(config -> {
            if (config.getSceneRule() != null) {
                log.info("  [Workspace] 场景规则: {} (关键词: {})",
                        config.getSceneRule().getSceneName(), config.getSceneRule().getKeywords());
            }
            if (config.getKnowledgeFiles() != null && !config.getKnowledgeFiles().isEmpty()) {
                log.info("  [Workspace] 知识文件: {}", config.getKnowledgeFiles());
            }
            if (config.getSkillNames() != null && !config.getSkillNames().isEmpty()) {
                log.info("  [Workspace] 技能: {}", config.getSkillNames());
            }
            if (config.getSubAgentIds() != null && !config.getSubAgentIds().isEmpty()) {
                log.info("  [Workspace] 子智能体: {}", config.getSubAgentIds());
            }
        });
    }

    // ========== Toolkit 工具组修正 ==========

    /**
     * 获取 Agent 内部的 Toolkit 实例。
     * <p>
     * HarnessAgent.Builder.build() 内部会将 Toolkit 深拷贝传给 ReActAgent，
     * 内置工具注册在拷贝上。外部持有的原始 Toolkit 不包含这些工具，
     * 需要通过 {@link HarnessAgent#getDelegate()}.{@code getToolkit()} 获取。
     * </p>
     *
     * @param agent 构建完成的 Agent 实例
     * @return Agent 内部 Toolkit，如果无法获取则返回 null
     */
    private Toolkit resolveAgentToolkit(Agent agent) {
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.getDelegate().getToolkit();
        }
        return null;
    }

    /**
     * 将未分组工具分配到指定组。
     * <p>
     * HarnessAgent/ReActAgent 的内置工具通过 {@code Toolkit.registerTool(Object)}
     * 注册时不指定组名，导致它们成为 "ungrouped"。未分组工具不受组激活控制，
     * 且日志中产生大量噪音。
     * </p>
     * <p>
     * 通过反射调用 package-private 的 ToolGroupManager 方法来实现分配，
     * 避免修改框架源码。框架升级时若字段名/方法签名变化，try-catch 保障容错。
     * </p>
     *
     * @param toolkit     目标 Toolkit
     * @param targetGroup 目标组名称
     */
    private void assignUngroupedTools(Toolkit toolkit, String fallbackGroup) {
        if (toolkit == null)
            return;
        try {
            Field groupManagerField = Toolkit.class.getDeclaredField("groupManager");
            groupManagerField.setAccessible(true);
            Object groupManager = groupManagerField.get(toolkit);

            Method isGroupedTool = groupManager.getClass()
                    .getDeclaredMethod("isGroupedTool", String.class);
            isGroupedTool.setAccessible(true);

            Method addToolToGroup = groupManager.getClass()
                    .getDeclaredMethod("addToolToGroup", String.class, String.class);
            addToolToGroup.setAccessible(true);

            int count = 0;
            for (String toolName : toolkit.getToolNames()) {
                if (!(boolean) isGroupedTool.invoke(groupManager, toolName)) {
                    String group = resolveLocalToolGroup(toolName);
                    if (group.equals(fallbackGroup)) {
                        // 确保回退组存在
                        try {
                            toolkit.createToolGroup(fallbackGroup, "默认本地工具组", true);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    addToolToGroup.invoke(groupManager, group, toolName);
                    count++;
                }
            }
            if (count > 0) {
                log.info("已分配 {} 个未分组工具到对应组", count);
            }
        } catch (Exception e) {
            log.warn("分配未分组工具失败，回退到默认行为", e);
        }
    }
}
