package io.yunxi.platform.framework.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.yunxi.platform.framework.agent.extension.AgentCustomizer;
import io.yunxi.platform.framework.embedding.ChatModelProvider;
import io.yunxi.platform.framework.embedding.ModelConfig;
import io.yunxi.platform.framework.embedding.ModelProviderFactory;
import io.yunxi.platform.framework.mcp.McpToolRegistry;
import io.yunxi.platform.framework.skill.SkillRegistryService;
import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolAdapter;
import io.yunxi.platform.framework.tool.ToolCircuitBreaker;
import io.yunxi.platform.framework.tool.ToolRegistry;
import io.yunxi.platform.shared.config.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 自动装配引擎 — 配置驱动的 Agent 初始化
 * <p>
 * 应用启动时执行两轮初始化：
 * <ol>
 * <li>第一轮：初始化所有独立 Agent，包含 MCP 工具注册/本地工具/SkillBox 等</li>
 * <li>第二轮：根据 orchestration 配置创建编排 Agent（Supervisor 等）</li>
 * </ol>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AgentConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigurer.class);

    private final AgentDefinitionLoader definitionLoader;
    private final AgentDomainService agentDomainService;
    private final AgentscopeCoreProperties coreProperties;
    private final AgentBuilderHelper builderHelper;
    private final SupervisorService supervisorService;
    private final ModelProviderFactory modelProviderFactory;
    private final McpToolRegistry mcpToolRegistry;
    private final ToolRegistry toolRegistry;
    private final ToolCircuitBreaker circuitBreaker;

    @Autowired(required = false)
    private ObjectProvider<SkillRegistryService> skillRegistryProvider;
    @Autowired(required = false)
    private ObjectProvider<AgentCustomizer> customizerProvider;

    public AgentConfigurer(AgentDefinitionLoader definitionLoader,
            AgentDomainService agentDomainService,
            AgentscopeCoreProperties coreProperties,
            AgentBuilderHelper builderHelper,
            SupervisorService supervisorService,
            ModelProviderFactory modelProviderFactory,
            McpToolRegistry mcpToolRegistry,
            ToolRegistry toolRegistry,
            ToolCircuitBreaker circuitBreaker) {
        this.definitionLoader = definitionLoader;
        this.agentDomainService = agentDomainService;
        this.coreProperties = coreProperties;
        this.builderHelper = builderHelper;
        this.supervisorService = supervisorService;
        this.modelProviderFactory = modelProviderFactory;
        this.mcpToolRegistry = mcpToolRegistry;
        this.toolRegistry = toolRegistry;
        this.circuitBreaker = circuitBreaker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void configureAgents() {
        log.info("AgentConfigurer: 开始自动装配 Agent...");
        List<AgentDefinition> definitions = definitionLoader.getAgentDefinitions();
        if (definitions.isEmpty()) {
            log.warn("没有找到 Agent 配置（agent-definitions/ 目录为空）");
            return;
        }
        for (AgentDefinition def : definitions) {
            if (!isOrchestrated(def))
                initializeSingleAgent(def);
        }
        for (AgentDefinition def : definitions) {
            if (isOrchestrated(def))
                createOrchestratedAgent(def);
        }
        log.info("AgentConfigurer: Agent 自动装配完成，共 {} 个 Agent", agentDomainService.countAgents());
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
            builderHelper.configureStructuredOutput(modelCfg, def);
            ChatModelProvider modelProvider = modelProviderFactory.createProvider(modelCfg);
            String prompt = def.getPrompt();

            ReActAgent.Builder builder = ReActAgent.builder()
                    .name(def.getName()).sysPrompt(prompt).model(modelProvider);

            // 创建 Toolkit 并注册所有工具
            Toolkit toolkit = new Toolkit();
            registerLocalTools(toolkit);
            registerMcpTools(toolkit, def);
            builder.toolkit(toolkit);

            builderHelper.injectStandardHooks(builder, toolkit);
            builderHelper.injectHITLHooks(builder, toolkit, def);

            if (def.getRuntime() != null) {
                builder.maxIters(def.getRuntime().getMaxIterations());
                if (def.getRuntime().isEnableMetaTool())
                    builder.enableMetaTool(true);
            }
            if (def.getPlan() != null && def.getPlan().isEnabled()) {
                PlanNotebook.Builder pb = PlanNotebook.builder()
                        .needUserConfirm(def.getPlan().isUserConfirm())
                        .planToHint(new ChinesePlanToHint());
                if (def.getPlan().getMaxSubtasks() != null)
                    pb.maxSubtasks(def.getPlan().getMaxSubtasks());
                builder.planNotebook(pb.build());
            }

            // SkillBox 注入
            injectSkillBox(builder, toolkit, def);

            // 扩展点
            AgentCustomizer customizer = findCustomizer(def);
            ReActAgent agent = customizer != null ? customizer.customize(def, builder) : builder.build();

            // 工具组激活策略
            applyToolGroupActivation(toolkit, def);

            // 注册
            agentDomainService.registerAgentInstance(def.getName(), agent);
            agentDomainService.registerAgentInfoDto(def.getName(), description(def), prompt, modelCfg.getModelName());
            agentDomainService.registerAgentRagMode(def.getName(), def.getRagMode());
            registerStructuredOutputSchema(def);
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
        Map<String, ReActAgent> expertAgents = new HashMap<>();
        for (ExpertConfig expert : experts) {
            try {
                ReActAgent agent = agentDomainService.getAgentInstance(expert.getName());
                expertAgents.put(expert.getName(), agent);
                supervisorService.registerExpertAgent(expert.getName(), agent);
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
        for (Map.Entry<String, ReActAgent> entry : expertAgents.entrySet()) {
            toolkit.registration()
                    .subAgent(() -> entry.getValue(), SubAgentConfig.builder().forwardEvents(false).build())
                    .group("agent").apply();
        }
        registerMcpTools(toolkit, def);

        ReActAgent.Builder builder = ReActAgent.builder()
                .name(def.getName()).sysPrompt(def.getPrompt()).model(modelProvider).toolkit(toolkit);
        builderHelper.injectStandardHooks(builder, toolkit);
        builderHelper.injectHITLHooks(builder, toolkit, def);

        if (def.getRuntime() != null) {
            builder.maxIters(def.getRuntime().getMaxIterations());
            if (def.getRuntime().isEnableMetaTool())
                builder.enableMetaTool(true);
        }
        if (def.getPlan() != null && def.getPlan().isEnabled()) {
            PlanNotebook.Builder pb = PlanNotebook.builder()
                    .needUserConfirm(def.getPlan().isUserConfirm())
                    .planToHint(new ChinesePlanToHint());
            if (def.getPlan().getMaxSubtasks() != null)
                pb.maxSubtasks(def.getPlan().getMaxSubtasks());
            builder.planNotebook(pb.build());
        }

        injectSkillBox(builder, toolkit, def);
        AgentCustomizer customizer = findCustomizer(def);
        ReActAgent supervisor = customizer != null ? customizer.customize(def, builder) : builder.build();

        applyToolGroupActivation(toolkit, def);
        agentDomainService.registerAgentInstance(def.getName(), supervisor);
        agentDomainService.registerAgentInfoDto(def.getName(), description(def), def.getPrompt(),
                modelCfg.getModelName());
        agentDomainService.registerAgentRagMode(def.getName(), def.getRagMode());
        supervisorService.registerSupervisorConfig(def.getName(), experts.stream().map(ExpertConfig::getName).toList());
        registerStructuredOutputSchema(def);
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

    // ========== SkillBox / 结构化输出 / 工具组激活 ==========

    private void injectSkillBox(ReActAgent.Builder builder, Toolkit toolkit, AgentDefinition def) {
        SkillRegistryService registry = skillRegistryProvider != null ? skillRegistryProvider.getIfAvailable() : null;
        if (registry == null)
            return;
        List<String> enabled = def.getTools() != null ? def.getTools().getEnabledSkills() : null;
        SkillBox skillBox = registry.createSkillBox(enabled, toolkit);
        builder.skillBox(skillBox);
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

    private void registerStructuredOutputSchema(AgentDefinition def) {
        // 结构化输出 schema 的注册逻辑已在 builderHelper.configureStructuredOutput 中处理
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
}
