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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.yunxi.platform.framework.skill.SkillRegistryService;
import io.yunxi.platform.framework.tool.ToolGroupManager;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;

/**
 * 高级功能 Agent 工厂服务
 * <p>
 * 本服务负责创建支持高级功能的 Agent 实例，直接使用AgentScope SDK原生API。用户可以通过请求配置启用以下高级功能：
 * </p>
 * <ul>
 * <li><b>RAG 知识库</b>：使用AgentScope 官方扩展（BailianKnowledge、DifyKnowledge 等）</li>
 * <li><b>长期记忆</b>：使用AgentScope 官方扩展（Mem0、AutoContextMemory 等）</li>
 * <li><b>工具调用</b>：使用AgentScope Toolkit API</li>
 * <li><b>Studio 可视化</b>：自动注入StudioMessageHook</li>
 * </ul>
 *
 * <h3>知识库自动配置（推荐方式）</h3>
 *
 * <p>
 * 配置 {@code agentscope.extensions.autoConfigEnabled=true} 后，只需在
 * {@code agentscope.yml}
 * 中声明知识库配置，框架会自动创建 Knowledge 实例并注册为 Spring Bean。
 * </p>
 *
 * <pre>
 * # agentscope.yml
 * agentscope:
 *   extensions:
 *     autoConfigEnabled: true
 *     knowledge-bases:
 *       tech-docs:
 *         enabled: true
 *         type: bailian
 *         access-key-id: ${BAILIAN_ACCESS_KEY_ID}
 *         access-key-secret: ${BAILIAN_ACCESS_KEY_SECRET}
 *         workspace-id: ${BAILIAN_WORKSPACE_ID}
 *         index-id: ${BAILIAN_INDEX_ID}
 * </pre>
 *
 * <h3>手动 @Bean 方式（备用）</h3>
 *
 * <p>
 * 如需更精细的控制（如自定义检索参数），仍可使用 {@code @Bean} 手动创建：
 * </p>
 *
 * <pre>
 * &#64;Configuration
 * public class AgentscopeConfiguration {
 *     &#64;Bean
 *     public Knowledge productKnowledge(
 *             &#64;Value("${agentscope.knowledge-bases.product.access-key-id}") String accessKeyId,
 *             &#64;Value("${agentscope.knowledge-bases.product.access-key-secret}") String accessKeySecret,
 *             &#64;Value("${agentscope.knowledge-bases.product.workspace-id}") String workspaceId,
 *             &#64;Value("${agentscope.knowledge-bases.product.index-id}") String indexId) {
 *         return BailianKnowledge.builder()
 *                 .config(BailianConfig.builder()
 *                         .accessKeyId(accessKeyId)
 *                         .accessKeySecret(accessKeySecret)
 *                         .workspaceId(workspaceId)
 *                         .indexId(indexId)
 *                         .build())
 *                 .build();
 *     }
 * }
 * </pre>
 *
 * <h3>API 请求中引用知识库</h3>
 *
 * <pre>
 * POST /api/chat
 * {
 *   "message": "查询产品信息",
 *   "ragMode": "GENERIC",
 *   "knowledgeBases": ["techDocs"]
 * }
 * </pre>
 *
 * <h3>支持AgentScope官方扩展</h3>
 *
 * <h4>RAG 知识库：</h4>
 * <ul>
 * <li><b>agentscope-extensions-rag-bailian</b> - 阿里云百炼知识库</li>
 * <li><b>agentscope-extensions-rag-dify</b> - Dify 知识库</li>
 * <li><b>agentscope-extensions-rag-haystack</b> - HayStack 知识库</li>
 * <li><b>agentscope-extensions-rag-ragflow</b> - RAGFlow 知识库</li>
 * <li><b>agentscope-extensions-rag-simple</b> - 简单知识库（内存）</li>
 * </ul>
 *
 * <h4>长期记忆：</h4>
 * <ul>
 * <li><b>agentscope-extensions-mem0</b> - Mem0 云端记忆</li>
 * <li><b>agentscope-extensions-reme</b> - ReMe 记忆</li>
 * <li><b>agentscope-extensions-autocontext-memory</b> - 自动上下文记忆</li>
 * </ul>
 *
 * <h4>A2A 协议：</h4>
 * <ul>
 * <li><b>agentscope-extensions-a2a-client</b> - A2A 客户端</li>
 * <li><b>agentscope-extensions-a2a-server</b> - A2A 服务端</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 3.2.0
 * @see <a href="https://java.agentscope.io">AgentScope Java 文档</a>
 * @see io.yunxi.platform.infra.config.KnowledgeAutoConfiguration
 */
@Service
public class AdvancedAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AdvancedAgentFactory.class);

    /**
     * Agent 构建助手
     */
    @Autowired
    private AgentBuilderHelper builderHelper;

    /**
     * Spring Bean 中的知识库实例映射
     * 用户通过 @Bean 创建的知识库会自动注入到这里
     *
     * 必须保留 required=false！Spring 对 Map 注入的行为与 List 不同：
     * 无 Bean 时 List 自动为空列表，但 Map 会报错。此处知识库为可选，可能不存在。
     */
    @Autowired(required = false)
    private Map<String, Knowledge> knowledgeBeans = new HashMap<>();

    /**
     * Spring Bean 中的长期记忆实例映射
     *
     * 必须保留 required=false，原因同上（Map 注入特性）。
     */
    @Autowired(required = false)
    private Map<String, io.agentscope.core.memory.LongTermMemory> memoryBeans = new HashMap<>();

    /**
     * Skill 注册中心（可选，用于按需创建过滤 SkillBox）
     */
    @Autowired
    private ObjectProvider<SkillRegistryService> skillRegistryProvider;

    /**
     * 工具分组管理器
     */
    @Autowired
    private ObjectProvider<ToolGroupManager> toolGroupManagerProvider;

    /** 默认检索配置（来自 YAML 或硬编码默认值） */
    private RetrieveConfig defaultRetrieveConfig;

    /** Agent 领域服务 */
    private final AgentDomainService agentDomainService;

    /** AgentScope 扩展配置（含检索默认参数） */
    private final AgentscopeExtensionProperties extensionProperties;

    /**
     * 构造高级 Agent 工厂
     *
     * @param agentDomainService  Agent 领域服务
     * @param extensionProperties AgentScope 扩展配置
     */
    public AdvancedAgentFactory(AgentDomainService agentDomainService,
            AgentscopeExtensionProperties extensionProperties) {
        this.agentDomainService = agentDomainService;
        this.extensionProperties = extensionProperties;
        this.defaultRetrieveConfig = buildDefaultRetrieveConfig();
    }

    /**
     * 从 YAML 配置构建默认检索配置
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
     * 创建临时高级功能 Agent
     *
     * @param baseAgentName 基础 Agent 名称
     * @param request       请求配置
     * @return 创建的Agent实例（如果创建失败返回null）
     */
    public ReActAgent createTempAgent(String baseAgentName, UnifiedChatRequest request) {
        try {
            long startTime = System.currentTimeMillis();
            Map<String, Object> features = detectAdvancedFeatures(request);
            log.info("开始创建临时高级Agent: baseAgent={}, features={}", baseAgentName, features);

            // 获取基础 Agent 配置
            ReActAgent baseAgent = agentDomainService.getAgentInstance(baseAgentName);

            // 获取基础 Model
            Model baseModel = baseAgent.getModel();

            // 创建 Agent 构建器
            ReActAgent.Builder builder = ReActAgent.builder()
                    .name(baseAgentName + "-temp-" + System.currentTimeMillis())
                    .sysPrompt(baseAgent.getSysPrompt())
                    .model(baseModel);

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
            applyModelConfig(request, baseModel); // 应用动态模型参数
            builder.toolkit(toolkit);

            // 注入标准 Hook 集合（Studio + TextToolCallParser）
            builderHelper.injectStandardHooks(builder, toolkit);

            // 构建 Agent
            ReActAgent agent = builder.build();

            long duration = System.currentTimeMillis() - startTime;
            log.info("临时高级Agent创建成功: agentId={}, name={}, 耗时{}ms",
                    agent.getAgentId(), agent.getName(), duration);

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
     * 检测请求中的高级功能
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
     * 应用 RAG 配置
     * <p>
     * 从Spring Bean容器中获取Knowledge实例。
     * 知识库可通过自动配置（推荐）或手动 @Bean 方式注册：
     * </p>
     * <ol>
     * <li><b>自动配置</b>（推荐）：{@code agentscope.extensions.autoConfigEnabled=true}，
     * YAML 配置的知识库自动注册为 Bean</li>
     * <li><b>手动 @Bean</b>（备用）：在 @Configuration 类中手动创建</li>
     * </ol>
     *
     * <pre>
     * &#64;Bean
     * public Knowledge productKnowledge() {
     *     return BailianKnowledge.builder()
     *             .config(BailianConfig.builder()
     *                     .accessKeyId("...")
     *                     .accessKeySecret("...")
     *                     .workspaceId("...")
     *                     .indexId("...")
     *                     .build())
     *             .build();
     * }
     * </pre>
     */
    private void applyRAGConfig(UnifiedChatRequest request, ReActAgent.Builder builder) {
        String ragMode = request.getRagMode();
        if (ragMode == null || "NONE".equals(ragMode)) {
            return;
        }

        try {
            RAGMode mode = parseRAGMode(ragMode);
            log.info("配置RAG: mode={}, knowledgeBases={}", mode, request.getKnowledgeBases());

            // 从Spring Bean中获取知识库
            Set<Knowledge> knowledgeBases = getKnowledgeBases(request.getKnowledgeBases());
            if (knowledgeBases.isEmpty()) {
                log.warn("未找到知识库实例，RAG功能可能无法正常工作");
                log.info("提示：请在 agentscope.yml 中配置知识库（autoConfigEnabled=true），或通过 @Bean 手动创建");
                return;
            }

            // 添加知识库到 Builder
            for (Knowledge knowledge : knowledgeBases) {
                builder.knowledge(knowledge);
                log.info("添加知识库到Agent: {}", knowledge);
            }

            // 设置 RAG 模式
            builder.ragMode(mode);

            // 设置检索配置
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
     * 从Spring Bean中获取知识库实例
     */
    private Set<Knowledge> getKnowledgeBases(List<String> knowledgeBaseNames) {
        if (knowledgeBaseNames == null || knowledgeBaseNames.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Knowledge> knowledgeSet = new HashSet<>();
        for (String beanName : knowledgeBaseNames) {
            if (knowledgeBeans != null && knowledgeBeans.containsKey(beanName)) {
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
     */
    private void applyToolConfig(UnifiedChatRequest request, Toolkit toolkit) {
        // 初始化工具组（本地工具组 + MCP 工具组由注册流程创建）
        if (toolGroupManagerProvider.getIfAvailable() != null) {
            toolGroupManagerProvider.getIfAvailable().createLocalToolGroups(toolkit);
        }

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

            // 应用工具自动归组（已由注册流程完成分组，此处仅记录）
            if (toolGroupManagerProvider.getIfAvailable() != null) {
                log.debug("工具组已由注册流程创建，运行时组: {}",
                        toolGroupManagerProvider.getIfAvailable().getRuntimeGroups().keySet());
            }

            // 处理请求中指定的工具组
            List<String> requestedGroups = request.getEnabledToolGroups();
            if (requestedGroups != null && !requestedGroups.isEmpty()) {
                log.info("请求激活工具组: {}", requestedGroups);
                if (toolGroupManagerProvider.getIfAvailable() != null) {
                    toolGroupManagerProvider.getIfAvailable().activateGroups(toolkit, requestedGroups);
                }
            }

            log.info("工具启用完成: 成功{}个", enabledCount);

        } catch (Exception e) {
            log.error("应用工具配置失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 应用技能配置
     *
     * <p>
     * 通过 SkillRegistryService 创建按需过滤的 SkillBox，
     * 仅注入请求中指定的技能描述，减少 prompt token 消耗。
     * </p>
     */
    private void applySkillConfig(UnifiedChatRequest request, ReActAgent.Builder builder, Toolkit toolkit) {
        if (skillRegistryProvider == null) {
            log.debug("SkillRegistryService 未配置，跳过技能注入");
            return;
        }

        try {
            List<String> enabledSkills = request.getEnabledSkills();
            SkillBox agentSkillBox = skillRegistryProvider.getIfAvailable().createSkillBox(enabledSkills, toolkit);
            builder.skillBox(agentSkillBox);

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
     */
    private void applyMemoryConfig(UnifiedChatRequest request, ReActAgent.Builder builder) {
        String memoryMode = request.getMemoryMode();
        if (memoryMode == null || "NONE".equals(memoryMode)) {
            return;
        }

        try {
            log.info("配置记忆: mode={}", memoryMode);

            if ("IN_MEMORY".equals(memoryMode.toUpperCase())) {
                Memory memory = new InMemoryMemory();
                builder.memory(memory);
                log.info("短期记忆配置完成");
                return;
            }

            // 长期记忆 - 优先使用请求指定的，否则使用默认的 ReMeLongTermMemory
            io.agentscope.core.memory.LongTermMemory memory = null;

            if (request.getLongTermMemory() != null && memoryBeans != null) {
                memory = memoryBeans.get(request.getLongTermMemory());
                if (memory != null) {
                    log.info("使用请求指定的长期记忆: {}", request.getLongTermMemory());
                } else {
                    log.warn("请求指定的记忆Bean未找到: {}", request.getLongTermMemory());
                }
            }

            // 未指定或未找到时，使用默认的 ReMeLongTermMemory
            if (memory == null && memoryBeans != null && memoryBeans.containsKey("reMeLongTermMemory")) {
                memory = memoryBeans.get("reMeLongTermMemory");
                log.info("使用默认的 ReMeLongTermMemory");
            }

            if (memory != null) {
                builder.longTermMemory(memory)
                        .longTermMemoryMode(io.agentscope.core.memory.LongTermMemoryMode.BOTH);
                // BOTH = StaticLongTermMemoryHook (自动 record/retrieve) + LongTermMemoryTools
                // (Agent 可调用)
                log.info("长期记忆配置完成: mode=BOTH (自动 Hook + Agent Tool)");
            } else {
                log.warn("未找到可用的长期记忆 Bean");
                log.info("提示：请通过 @Bean 创建 LongTermMemory 实例");
            }

        } catch (Exception e) {
            log.error("应用记忆配置失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 应用执行配置
     */
    private void applyExecutionConfig(UnifiedChatRequest request, ReActAgent.Builder builder) {
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
            log.info("配置enablePlanNotebook: true - Agent 将自动拆解复杂任务（中文模式）");
        }
    }

    /**
     * 应用动态模型参数配置
     * <p>
     * 注意：此方法尝试应用动态模型参数。如果AgentScope SDK 的Model 不支持某些参数，
     * 会记录警告日志但不中断执行
     * </p>
     *
     * <p>
     * 支持的参数：
     * <ul>
     * <li>temperature - 温度参数</li>
     * <li>maxTokens - 最大Token</li>
     * <li>topP - Top-P 采样（如Model 支持）</li>
     * <li>presencePenalty - 存在惩罚（如Model 支持）</li>
     * <li>frequencyPenalty - 频率惩罚（如Model 支持）</li>
     * <li>stopSequences - 停止序列（如Model 支持）</li>
     * </ul>
     * </p>
     */
    private void applyModelConfig(UnifiedChatRequest request, Model baseModel) {
        boolean hasDynamicParams = false;

        // 记录动态参数
        if (request.getTemperature() != null) {
            log.info("动态参数: temperature = {}", request.getTemperature());
            hasDynamicParams = true;
        }
        if (request.getMaxTokens() != null) {
            log.info("动态参数: maxTokens = {}", request.getMaxTokens());
            hasDynamicParams = true;
        }
        if (request.getTopP() != null) {
            log.info("动态参数: topP = {}", request.getTopP());
            hasDynamicParams = true;
        }
        if (request.getPresencePenalty() != null) {
            log.info("动态参数: presencePenalty = {}", request.getPresencePenalty());
            hasDynamicParams = true;
        }
        if (request.getFrequencyPenalty() != null) {
            log.info("动态参数: frequencyPenalty = {}", request.getFrequencyPenalty());
            hasDynamicParams = true;
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            log.info("动态参数: stopSequences = {}", request.getStopSequences());
            hasDynamicParams = true;
        }

        if (hasDynamicParams) {
            log.info("注意：AgentScope SDK 的Model 实例通常是配置后不可变的");
            log.info("动态模型参数可能需要通过重新创建 Model 来实现");
            log.info("如果参数未生效，请在 Agent 定义中配置这些参数");

            // 尝试通过反射设置参数（如Model 支持）
            trySetModelParameter(baseModel, "temperature", request.getTemperature());
            trySetModelParameter(baseModel, "maxTokens", request.getMaxTokens());
            trySetModelParameter(baseModel, "topP", request.getTopP());
            trySetModelParameter(baseModel, "presencePenalty", request.getPresencePenalty());
            trySetModelParameter(baseModel, "frequencyPenalty", request.getFrequencyPenalty());
            trySetModelParameter(baseModel, "stopSequences", request.getStopSequences());
        }
    }

    /**
     * 尝试通过反射设置 Model 参数
     *
     * @param model      Model 实例
     * @param paramName  参数名
     * @param paramValue 参数值
     */
    private void trySetModelParameter(Model model, String paramName, Object paramValue) {
        if (paramValue == null) {
            return;
        }

        try {
            // 尝试查找对应的setter 方法
            String setterName = "set" + paramName.substring(0, 1).toUpperCase() + paramName.substring(1);
            var setter = model.getClass().getMethod(setterName, paramValue.getClass());
            setter.invoke(model, paramValue);
            log.debug("成功设置模型参数: {} = {}", paramName, paramValue);
        } catch (NoSuchMethodException e) {
            log.debug("Model 不支持参数{} (无setter方法): {}", paramName, e.getMessage());
        } catch (Exception e) {
            log.warn("设置模型参数失败: {} = {}, 原因: {}", paramName, paramValue, e.getMessage());
        }
    }

    /**
     * 解析 RAG 模式
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
