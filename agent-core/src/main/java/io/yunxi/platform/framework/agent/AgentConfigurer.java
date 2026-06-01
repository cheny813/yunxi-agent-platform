package io.yunxi.platform.framework.agent;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.yunxi.platform.framework.agent.extension.AgentCustomizer;
import io.yunxi.platform.framework.embedding.ChatModelProvider;
import io.yunxi.platform.framework.embedding.ModelConfig;
import io.yunxi.platform.framework.embedding.ModelProviderFactory;
import io.yunxi.platform.framework.hitl.HumanToolRegistrar;
import io.yunxi.platform.framework.hitl.ReasoningReviewHook;
import io.yunxi.platform.framework.hitl.ToolGateHook;
import io.yunxi.platform.framework.hook.TextToolCallParserHook;
import io.yunxi.platform.framework.mcp.McpToolRegistry;
import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolAdapter;
import io.yunxi.platform.framework.tool.ToolCircuitBreaker;
import io.yunxi.platform.framework.tool.ToolRegistry;
import io.yunxi.platform.framework.workspace.WorkspaceAutoDiscoveryEngine;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.ExpertConfig;
import io.yunxi.platform.shared.config.ExtensionConfig;
import io.yunxi.platform.shared.config.StageConfig;

/**
 * Agent 自动装配引擎 — 配置驱动的 Agent 初始化
 * <p>
 * 应用启动时执行两轮初始化：
 * <ol>
 * <li>第一轮：初始化所有独立 Agent，包含 MCP 工具注册/本地工具/SkillBox 等</li>
 * <li>第二轮：根据 orchestration 配置创建编排 Agent（Supervisor 等）</li>
 * </ol>
 * 所有 Agent 通过 {@link HarnessAgent.Builder} 构建。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AgentConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigurer.class);

    /** Agent 定义加载器 — 读取 agent-definitions/ 目录下的 YAML 配置 */
    private final AgentDefinitionLoader definitionLoader;

    /** Agent 领域服务 — 注册 Agent 实例和信息 */
    private final AgentDomainService agentDomainService;

    /** AgentScope 核心配置属性（API Key、模型名称、默认提示词等） */
    private final AgentscopeCoreProperties coreProperties;

    /** 模型提供商工厂 — 根据 ModelConfig 创建 ChatModelProvider */
    private final ModelProviderFactory modelProviderFactory;

    /** MCP 工具注册表 — 加载和注册 MCP 服务器工具 */
    private final McpToolRegistry mcpToolRegistry;

    /** 本地工具注册表 — 获取启用状态的本地工具 */
    private final ToolRegistry toolRegistry;

    /** 工具熔断器 — 工具调用异常保护 */
    private final ToolCircuitBreaker circuitBreaker;

    /** Agent 工作区初始化器 — 创建 AGENTS.md、knowledge/ 等目录结构 */
    private final AgentWorkspaceInitializer workspaceInitializer;

    /** Studio 消息 Hook 提供者（可选） */
    private final ObjectProvider<StudioMessageHook> studioMessageHookProvider;

    /** Agent 自定义装配器（可选）— 构建后处理 */
    private final ObjectProvider<AgentCustomizer> customizerProvider;

    /** 工作区自动发现引擎 */
    private final WorkspaceAutoDiscoveryEngine workspaceDiscoveryEngine;

    public AgentConfigurer(AgentDefinitionLoader definitionLoader,
            AgentDomainService agentDomainService,
            AgentscopeCoreProperties coreProperties,
            ModelProviderFactory modelProviderFactory,
            McpToolRegistry mcpToolRegistry,
            ToolRegistry toolRegistry,
            ToolCircuitBreaker circuitBreaker,
            AgentWorkspaceInitializer workspaceInitializer,
            ObjectProvider<StudioMessageHook> studioMessageHookProvider,
            ObjectProvider<AgentCustomizer> customizerProvider,
            WorkspaceAutoDiscoveryEngine workspaceDiscoveryEngine) {
        this.definitionLoader = definitionLoader;
        this.agentDomainService = agentDomainService;
        this.coreProperties = coreProperties;
        this.modelProviderFactory = modelProviderFactory;
        this.mcpToolRegistry = mcpToolRegistry;
        this.toolRegistry = toolRegistry;
        this.circuitBreaker = circuitBreaker;
        this.workspaceInitializer = workspaceInitializer;
        this.studioMessageHookProvider = studioMessageHookProvider;
        this.customizerProvider = customizerProvider;
        this.workspaceDiscoveryEngine = workspaceDiscoveryEngine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void configureAgents() {
        log.info("AgentConfigurer: 开始自动装配 Agent...");
        List<AgentDefinition> definitions = definitionLoader.getAgentDefinitions();
        if (definitions.isEmpty()) {
            log.warn("没有找到 Agent 配置（agent-definitions/ 目录为空）");
            return;
        }

        // 第 0 步：初始化所有 Agent 工作区目录结构
        // （WorkspaceAutoDiscoveryEngine 随后会扫描读取这些目录）
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
            ModelConfig modelCfg = buildModelConfig(def);
            // 配置结构化输出 schema
            configureStructuredOutput(modelCfg, def);
            ChatModelProvider modelProvider = modelProviderFactory.createProvider(modelCfg);
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

            // 工具组激活策略
            applyToolGroupActivation(toolkit, def);

            // 注册
            agentDomainService.registerAgentInstance(def.getName(), agent);
            agentDomainService.registerAgentInfoDto(def.getName(), description(def), prompt, modelCfg.getModelName());
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

        ModelConfig modelCfg = buildModelConfig(def);
        ChatModelProvider modelProvider = modelProviderFactory.createProvider(modelCfg);

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

        applyToolGroupActivation(toolkit, def);
        agentDomainService.registerAgentInstance(def.getName(), supervisor);
        agentDomainService.registerAgentInfoDto(def.getName(), description(def), def.getPrompt(),
                modelCfg.getModelName());
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

    private void registerLocalTools(Toolkit toolkit) {
        if (toolRegistry == null)
            return;
        Collection<Tool> tools = toolRegistry.getEnabledTools();
        if (tools == null || tools.isEmpty())
            return;
        createLocalToolGroups(toolkit);
        for (Tool tool : tools) {
            try {
                toolkit.registration().agentTool(new ToolAdapter(tool, circuitBreaker))
                        .group(resolveLocalToolGroup(tool.getName())).apply();
            } catch (Exception e) {
                log.warn("注册本地工具失败: {}", tool.getName(), e);
            }
        }
    }

    private void registerMcpTools(Toolkit toolkit, AgentDefinition def) {
        if (def.getTools() == null || def.getTools().getMcpServers() == null)
            return;
        List<String> servers = def.getTools().getMcpServers();
        mcpToolRegistry.registerAgentToolkit(def.getName(), toolkit);
        mcpToolRegistry.loadAndRegisterMcpTools(def.getName(), servers);
    }

    private void createLocalToolGroups(Toolkit toolkit) {
        for (String[] g : new String[][] { { "agent", "子Agent调用工具" }, { "page", "页面生成工具" }, { "general", "通用本地工具" } }) {
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
     * 配置结构化输出 schema 到模型配置
     */
    private void configureStructuredOutput(ModelConfig modelConfig, AgentDefinition definition) {
        if (definition.getStructuredOutput() != null && definition.getStructuredOutput().isEnabled()) {
            String schema = definition.getStructuredOutput().getSchema();
            if (schema != null && !schema.isBlank()) {
                modelConfig.setStructuredOutputSchema(schema);
            }
        }
    }

    /**
     * 注入标准 Hook 集合（Studio + TextToolCallParser）
     */
    private void injectStandardHooks(HarnessAgent.Builder builder, Toolkit toolkit) {
        if (studioMessageHookProvider.getIfAvailable() != null) {
            builder.hook(studioMessageHookProvider.getIfAvailable());
        }
        TextToolCallParserHook textToolCallParserHook = new TextToolCallParserHook(toolkit);
        builder.hook(textToolCallParserHook);
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
        List<String> toolGroups = def.getTools() != null ? def.getTools().getGroups() : null;
        if (toolGroups == null || toolGroups.isEmpty())
            return;
        allGroups.forEach(g -> {
            try {
                toolkit.updateToolGroups(List.of(g), false);
            } catch (Exception ignored) {
            }
        });
        toolGroups.forEach(g -> {
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

    private ModelConfig buildModelConfig(AgentDefinition def) {
        ModelConfig cfg = def.getModel();
        if (cfg == null) {
            cfg = new ModelConfig(coreProperties.getProvider(), coreProperties.getApiKey(),
                    coreProperties.getModelName());
        } else {
            if (cfg.getApiKey() == null || cfg.getApiKey().isBlank())
                cfg.setApiKey(coreProperties.getApiKey());
            if (cfg.getModelName() == null || cfg.getModelName().isBlank())
                cfg.setModelName(coreProperties.getModelName());
            if (cfg.getProvider() == null || cfg.getProvider().isBlank())
                cfg.setProvider(coreProperties.getProvider());
        }
        if (cfg.getTemperature() == null)
            cfg.setTemperature(0.7);
        if (cfg.getMaxTokens() == null)
            cfg.setMaxTokens(4096);
        return cfg;
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
}
