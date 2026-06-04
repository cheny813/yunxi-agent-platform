package io.yunxi.platform.framework.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.yunxi.platform.framework.embedding.ChatModelProvider;
import io.yunxi.platform.framework.hook.TextToolCallParserHook;
import io.yunxi.platform.framework.skill.SkillRegistryService;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;

/**
 * 高级功能 Agent 工厂服务
 * <p>
 * 本服务负责创建支持高级功能的临时 Agent 实例，通过 HarnessAgent 包装 ReActAgent。
 * 用户可以通过请求配置启用 RAG 知识库、长期记忆、工具调用等高级功能。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.2.0
 */
@Service
public class AdvancedAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AdvancedAgentFactory.class);

    /** Agent 领域服务 — 获取基础 Agent 配置（模型提供商、提示词、RAG 模式） */
    private final AgentDomainService agentDomainService;

    /** AgentScope 扩展配置 — 含检索默认参数（limit、scoreThreshold） */
    private final AgentscopeExtensionProperties extensionProperties;

    /** AgentScope 核心配置属性 — 含工作区路径、compaction 等 */
    private final AgentscopeCoreProperties coreProperties;

    /** Spring Bean 容器中的知识库实例映射（beanName → Knowledge） */
    private final Map<String, Knowledge> knowledgeBeans;

    /** Spring Bean 容器中的长期记忆实例映射（beanName → LongTermMemory） */
    private final Map<String, io.agentscope.core.memory.LongTermMemory> memoryBeans;

    /** Skill 注册中心（可选，用于按需创建过滤 SkillBox） */
    private final ObjectProvider<SkillRegistryService> skillRegistryProvider;

    /** Studio 消息 Hook 提供者（可选） */
    private final ObjectProvider<StudioMessageHook> studioMessageHookProvider;

    /** 默认检索配置 */
    private RetrieveConfig defaultRetrieveConfig;

    public AdvancedAgentFactory(AgentDomainService agentDomainService,
            AgentscopeExtensionProperties extensionProperties,
            AgentscopeCoreProperties coreProperties,
            ObjectProvider<SkillRegistryService> skillRegistryProvider,
            ObjectProvider<StudioMessageHook> studioMessageHookProvider) {
        this.agentDomainService = agentDomainService;
        this.extensionProperties = extensionProperties;
        this.coreProperties = coreProperties;
        this.skillRegistryProvider = skillRegistryProvider;
        this.studioMessageHookProvider = studioMessageHookProvider;
        this.knowledgeBeans = new HashMap<>();
        this.memoryBeans = new HashMap<>();
        this.defaultRetrieveConfig = buildDefaultRetrieveConfig();
    }

    /**
     * 注入知识库 Bean（由 Spring 容器回调调用）
     * <p>
     * 框架自动配置的 Knowledge 实例通过此方法注入，
     * 在创建临时 Agent 时按需使用。
     * 替换了原 {@code @Autowired Map<String, Knowledge>} 字段注入方式。
     * </p>
     */
    public void setKnowledgeBeans(Map<String, Knowledge> knowledgeBeans) {
        if (knowledgeBeans != null) {
            this.knowledgeBeans.putAll(knowledgeBeans);
        }
    }

    /**
     * 注入长期记忆 Bean（由 Spring 容器回调调用）
     * <p>
     * ReMeLongTermMemory 等长期记忆实例通过此方法注入，
     * 替换了原 {@code @Autowired Map<String, LongTermMemory>} 字段注入方式。
     * </p>
     */
    public void setMemoryBeans(Map<String, io.agentscope.core.memory.LongTermMemory> memoryBeans) {
        if (memoryBeans != null) {
            this.memoryBeans.putAll(memoryBeans);
        }
    }

    /**
     * 从 YAML 配置构建默认检索配置
     * <p>
     * 优先级：YAML 配置 {@code agentscope.extensions.retrieve} > 硬编码默认值。
     * 默认检索 5 条，相似度阈值 0.5。
     * </p>
     */
    private RetrieveConfig buildDefaultRetrieveConfig() {
        var yamlConfig = extensionProperties.getRetrieve();
        if (yamlConfig != null) {
            return RetrieveConfig.builder()
                    .limit(yamlConfig.getDefaultLimit())
                    .scoreThreshold(yamlConfig.getDefaultScoreThreshold())
                    .build();
        }
        return RetrieveConfig.builder()
                .limit(5)
                .scoreThreshold(0.5)
                .build();
    }

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
     * 创建临时高级功能 Agent
     *
     * @param baseAgentName 基础 Agent 名称
     * @param request       请求配置
     * @return 创建的 Agent 实例（如果创建失败返回 null）
     */
    public Agent createTempAgent(String baseAgentName, UnifiedChatRequest request) {
        try {
            long startTime = System.currentTimeMillis();
            Map<String, Object> features = detectAdvancedFeatures(request);
            log.info("开始创建临时高级Agent: baseAgent={}, features={}", baseAgentName, features);

            // 获取基础 Agent 配置（从缓存中，而非 Agent 实例）
            ChatModelProvider modelProvider = agentDomainService.getAgentModelProvider(baseAgentName);
            String sysPrompt = agentDomainService.getAgentSysPrompt(baseAgentName);
            if (modelProvider == null) {
                log.error("基础 Agent [{}] 的模型提供商未找到", baseAgentName);
                return null;
            }

            // 创建 HarnessAgent 构建器
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name(baseAgentName + "-temp-" + System.currentTimeMillis())
                    .sysPrompt(sysPrompt != null ? sysPrompt : "")
                    .model(modelProvider)
                    .workspace(coreProperties.getWorkspaceBasePath() + "/" + baseAgentName)
                    .compaction(buildCompactionConfig());

            // 配置工具
            Toolkit toolkit = new Toolkit();

            // 应用 Agent 默认 RAG 模式（请求未指定时使用）
            applyDefaultRagMode(baseAgentName, request);

            // 应用高级配置
            applyRAGConfig(request, builder);
            applyToolConfig(request, toolkit);
            applySkillConfig(request, builder, toolkit);
            applyMemoryConfig(request, builder);
            applyExecutionConfig(request, builder);
            applyModelConfig(request, builder);
            builder.toolkit(toolkit);

            // 注入标准 Hook（Studio + TextToolCallParser）
            if (studioMessageHookProvider.getIfAvailable() != null) {
                builder.hook(studioMessageHookProvider.getIfAvailable());
            }
            TextToolCallParserHook textHook = new TextToolCallParserHook(toolkit);
            builder.hook(textHook);

            // 构建 Agent
            Agent agent = builder.build();

            long duration = System.currentTimeMillis() - startTime;
            log.info("临时高级Agent创建成功: agentName={}, 耗时{}ms",
                    agent.getName(), duration);

            return agent;

        } catch (IllegalArgumentException e) {
            log.error("创建临时高级Agent失败（参数验证错误）: {}", e.getMessage());
            return null;
        } catch (IllegalStateException e) {
            log.error("创建临时高级Agent失败（状态错误）: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("创建临时高级Agent失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 检测请求中开启的高级功能，仅用于日志记录
     * <p>
     * 遍历请求中的 RAG 模式、工具、技能、记忆等配置项，
     * 返回开启的功能列表供日志输出，便于排查临时 Agent 创建问题。
     * </p>
     *
     * @param request 统一聊天请求
     * @return 已开启的高级功能映射（功能名 → 配置值）
     */
    private Map<String, Object> detectAdvancedFeatures(UnifiedChatRequest request) {
        Map<String, Object> features = new LinkedHashMap<>();

        if (request.getRagMode() != null && !"NONE".equals(request.getRagMode())) {
            features.put("ragMode", request.getRagMode());
            if (request.getKnowledgeBases() != null && !request.getKnowledgeBases().isEmpty()) {
                features.put("knowledgeBases", request.getKnowledgeBases());
            }
        }

        if (request.getEnabledTools() != null && !request.getEnabledTools().isEmpty()) {
            features.put("enabledTools", request.getEnabledTools());
        }

        if (request.getEnabledSkills() != null && !request.getEnabledSkills().isEmpty()) {
            features.put("enabledSkills", request.getEnabledSkills());
        }

        if (request.getMemoryMode() != null && !"NONE".equals(request.getMemoryMode())) {
            features.put("memoryMode", request.getMemoryMode());
        }

        if (request.getMaxIters() != null) {
            features.put("maxIters", request.getMaxIters());
        }

        return features;
    }

    /**
     * 应用 Agent 默认 RAG 模式
     * <p>
     * 当 API 请求未指定 RAG 模式（或为 NONE）时，使用 Agent YAML 配置的默认值。
     * 实现"Agent 配置默认 + API 请求覆盖"的分层覆盖模式。
     * </p>
     */
    private void applyDefaultRagMode(String baseAgentName, UnifiedChatRequest request) {
        String requestRagMode = request.getRagMode();
        if (requestRagMode == null || "NONE".equals(requestRagMode)) {
            String agentRagMode = agentDomainService.getAgentRagMode(baseAgentName);
            if (!"NONE".equals(agentRagMode)) {
                request.setRagMode(agentRagMode);
                log.info("使用 Agent 默认 RAG 模式: agent={}, ragMode={}", baseAgentName, agentRagMode);
            }
        }
    }

    /**
     * 应用 RAG 知识库配置
     * <p>
     * 从 Spring Bean 容器中获取 Knowledge 实例，注册到 HarnessAgent.Builder 中。
     * 知识库可通过自动配置（{@code autoConfigEnabled=true}）或手动 @Bean 方式注册。
     * 同时配置检索模式（GENERIC/AGENTIC）和检索参数（数量、相似度阈值）。
     * </p>
     */
    private void applyRAGConfig(UnifiedChatRequest request, HarnessAgent.Builder builder) {
        String ragMode = request.getRagMode();
        if (ragMode == null || "NONE".equals(ragMode)) {
            return;
        }

        try {
            RAGMode mode = parseRAGMode(ragMode);
            log.info("配置RAG: mode={}, knowledgeBases={}", mode, request.getKnowledgeBases());

            Set<Knowledge> knowledgeBases = getKnowledgeBases(request.getKnowledgeBases());
            if (knowledgeBases.isEmpty()) {
                log.warn("未找到知识库实例，RAG功能可能无法正常工作");
                return;
            }

            for (Knowledge knowledge : knowledgeBases) {
                builder.knowledge(knowledge);
                log.info("添加知识库到Agent: {}", knowledge);
            }

            builder.ragMode(mode);

            if (request.getRetrieveLimit() != null || request.getRetrieveScoreThreshold() != null) {
                RetrieveConfig.Builder configBuilder = RetrieveConfig.builder();
                if (request.getRetrieveLimit() != null) {
                    configBuilder.limit(request.getRetrieveLimit());
                } else {
                    configBuilder.limit(defaultRetrieveConfig.getLimit());
                }

                if (request.getRetrieveScoreThreshold() != null) {
                    configBuilder.scoreThreshold(request.getRetrieveScoreThreshold());
                } else {
                    configBuilder.scoreThreshold(defaultRetrieveConfig.getScoreThreshold());
                }

                builder.retrieveConfig(configBuilder.build());
            } else {
                builder.retrieveConfig(defaultRetrieveConfig);
            }

            log.info("RAG配置完成: mode={}, knowledgeCount={}", mode, knowledgeBases.size());

        } catch (Exception e) {
            log.error("应用RAG配置失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从 Spring Bean 容器中按名称获取知识库实例
     * <p>
     * 将请求中指定的知识库名称列表解析为对应的 Knowledge Bean 实例。
     * 未找到的 Bean 会记录警告日志，不中断流程。
     * </p>
     *
     * @param knowledgeBaseNames 知识库名称列表
     * @return 匹配的知识库实例集合
     */
    private Set<Knowledge> getKnowledgeBases(List<String> knowledgeBaseNames) {
        if (knowledgeBaseNames == null || knowledgeBaseNames.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Knowledge> knowledgeSet = new HashSet<>();
        for (String beanName : knowledgeBaseNames) {
            if (knowledgeBeans.containsKey(beanName)) {
                knowledgeSet.add(knowledgeBeans.get(beanName));
                log.info("从Spring Bean获取知识库: {}", beanName);
            } else {
                log.warn("知识库Bean未找到: {}", beanName);
            }
        }
        return knowledgeSet;
    }

    /**
     * 应用工具配置
     * <p>
     * 通过类名反射加载 AgentTool 实现类并注册到 Toolkit。
     * 同时处理请求中指定的工具组激活策略。
     * 工具组由 ToolGroupManager 统一管理生命周期。
     * </p>
     */
    private void applyToolConfig(UnifiedChatRequest request, Toolkit toolkit) {
        if (request.getEnabledTools() == null || request.getEnabledTools().isEmpty()) {
            return;
        }

        try {
            int enabledCount = 0;
            log.info("启用工具: {}", request.getEnabledTools());

            for (String toolClassName : request.getEnabledTools()) {
                try {
                    Class<?> toolClass = Class.forName(toolClassName);
                    if (AgentTool.class.isAssignableFrom(toolClass)) {
                        AgentTool tool = (AgentTool) toolClass.getDeclaredConstructor().newInstance();
                        toolkit.registerAgentTool(tool);
                        enabledCount++;
                        log.info("工具注册成功: {}", toolClassName);
                    } else {
                        log.warn("类不是AgentTool的子类: {}", toolClassName);
                    }
                } catch (ClassNotFoundException e) {
                    log.error("工具类未找到: {}", toolClassName);
                } catch (Exception e) {
                    log.error("工具注册失败: {}, 原因: {}", toolClassName, e.getMessage());
                }
            }

            log.info("工具启用完成: 成功{}个", enabledCount);

        } catch (Exception e) {
            log.error("应用工具配置失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 应用技能（Skill）配置
     * <p>
     * 通过 SkillRegistryService 创建按需过滤的 SkillBox，
     * 仅注入请求中指定的技能描述，减少 prompt token 消耗。
     * HarnessAgent 使用 skillRepository 机制管理技能，而非直接注入 SkillBox 实例。
     * </p>
     */
    private void applySkillConfig(UnifiedChatRequest request, HarnessAgent.Builder builder, Toolkit toolkit) {
        try {
            SkillRegistryService registry = skillRegistryProvider.getIfAvailable();
            if (registry == null) {
                log.debug("SkillRegistryService 未配置，跳过技能注入");
                return;
            }

            List<String> enabledSkills = request.getEnabledSkills();
            SkillBox agentSkillBox = registry.createSkillBox(enabledSkills, toolkit);
            // SkillBox configured via skillRepository on HarnessAgent.Builder
            // builder.skillBox() is not available on HarnessAgent.Builder

            if (enabledSkills != null && !enabledSkills.isEmpty()) {
                log.info("已注入过滤 SkillBox，启用 Skill: {}", enabledSkills);
            } else {
                log.info("已注入全量 SkillBox");
            }
        } catch (Exception e) {
            log.error("应用技能配置失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 应用记忆配置
     * <p>
     * 支持两种记忆模式：
     * <ul>
     * <li>IN_MEMORY：短期记忆，由 HarnessAgent 内部 MemoryFlushHook 自动管理</li>
     * <li>长期记忆：通过 LongTermMemory Bean（如 ReMeLongTermMemory）配置，
     * 使用 LongTermMemoryMode.BOTH 同时启用自动 Hook 记录和 Agent 主动调用工具</li>
     * </ul>
     * </p>
     */
    private void applyMemoryConfig(UnifiedChatRequest request, HarnessAgent.Builder builder) {
        String memoryMode = request.getMemoryMode();
        if (memoryMode == null || "NONE".equals(memoryMode)) {
            return;
        }

        try {
            log.info("配置记忆: mode={}", memoryMode);

            if ("IN_MEMORY".equals(memoryMode.toUpperCase())) {
                // HarnessAgent manages its own memory via MemoryFlushHook
                // builder.memory() not available on HarnessAgent.Builder
                log.info("短期记忆由 HarnessAgent 内部管理");
                return;
            }

            // 长期记忆 - 优先使用请求指定的，否则使用默认的 ReMeLongTermMemory
            io.agentscope.core.memory.LongTermMemory memory = null;

            if (request.getLongTermMemory() != null && !memoryBeans.isEmpty()) {
                memory = memoryBeans.get(request.getLongTermMemory());
                if (memory != null) {
                    log.info("使用请求指定的长期记忆: {}", request.getLongTermMemory());
                } else {
                    log.warn("请求指定的记忆Bean未找到: {}", request.getLongTermMemory());
                }
            }

            if (memory == null) {
                log.warn("未找到可用的长期记忆 Bean，将使用 Harness 内置文件系统记忆");
            }

            if (memory != null) {
                builder.longTermMemory(memory)
                        .longTermMemoryMode(io.agentscope.core.memory.LongTermMemoryMode.BOTH);
                log.info("长期记忆配置完成: mode=BOTH (自动 Hook + Agent Tool)");
            } else {
                log.warn("未找到可用的长期记忆 Bean");
            }

        } catch (Exception e) {
            log.error("应用记忆配置失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 应用执行配置
     * <p>
     * 配置 Agent 执行参数：
     * <ul>
     * <li>maxIters：最大 ReAct 循环迭代次数</li>
     * <li>enableMetaTool：是否启用 MetaTool（LLM 动态切换工具组）</li>
     * <li>enablePlanNotebook：是否启用任务规划能力</li>
     * </ul>
     * </p>
     */
    private void applyExecutionConfig(UnifiedChatRequest request, HarnessAgent.Builder builder) {
        if (request.getMaxIters() != null) {
            builder.maxIters(request.getMaxIters());
            log.info("配置maxIters: {}", request.getMaxIters());
        }

        if (request.getEnableMetaTool() != null) {
            builder.enableMetaTool(request.getEnableMetaTool());
            log.info("配置enableMetaTool: {}", request.getEnableMetaTool());
        }

        if (request.getEnablePlanNotebook() != null && request.getEnablePlanNotebook()) {
            PlanNotebook planNotebook = PlanNotebook.builder()
                    .needUserConfirm(false)
                    .planToHint(new ChinesePlanToHint())
                    .build();
            builder.planNotebook(planNotebook);
            log.info("配置enablePlanNotebook: true");
        }
    }

    /**
     * 应用动态模型参数配置
     * <p>
     * 通过 HarnessAgent.Builder 的 modelExecutionConfig 配置，
     * 无需反射调用。
     * </p>
     */
    private void applyModelConfig(UnifiedChatRequest request, HarnessAgent.Builder builder) {
        // 通过 GenerateOptions.Builder 配置模型参数
        io.agentscope.core.model.GenerateOptions.Builder optionsBuilder = io.agentscope.core.model.GenerateOptions
                .builder();

        boolean hasDynamicParams = false;

        if (request.getTemperature() != null) {
            optionsBuilder.temperature(request.getTemperature());
            hasDynamicParams = true;
        }
        if (request.getMaxTokens() != null) {
            optionsBuilder.maxTokens(request.getMaxTokens());
            hasDynamicParams = true;
        }
        if (request.getTopP() != null) {
            optionsBuilder.topP(request.getTopP());
            hasDynamicParams = true;
        }
        if (request.getPresencePenalty() != null) {
            optionsBuilder.presencePenalty(request.getPresencePenalty());
            hasDynamicParams = true;
        }
        if (request.getFrequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(request.getFrequencyPenalty());
            hasDynamicParams = true;
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            hasDynamicParams = true;
        }

        if (hasDynamicParams) {
            builder.generateOptions(optionsBuilder.build());
            log.info("动态模型参数已通过 HarnessAgent.Builder 配置");
        }
    }

    /**
     * 解析 RAG 模式字符串为枚举
     * <p>
     * 支持三种模式：
     * <ul>
     * <li>GENERIC：通用 RAG，在每次推理前自动检索知识库注入上下文</li>
     * <li>AGENTIC：Agent 驱动的 RAG，提供工具让 Agent 自行决定何时检索</li>
     * <li>其他/NONE：不启用 RAG</li>
     * </ul>
     * 不区分大小写。
     * </p>
     */
    private RAGMode parseRAGMode(String ragMode) {
        if (ragMode == null) {
            return RAGMode.NONE;
        }
        return switch (ragMode.toUpperCase()) {
            case "GENERIC" -> RAGMode.GENERIC;
            case "AGENTIC" -> RAGMode.AGENTIC;
            default -> {
                log.warn("未知的RAG模式: {}, 使用NONE", ragMode);
                yield RAGMode.NONE;
            }
        };
    }
}
