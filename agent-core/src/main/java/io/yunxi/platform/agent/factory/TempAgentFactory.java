package io.yunxi.platform.agent.factory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;

/**
 * 临时 Agent 工厂，创建支持高级功能的 HarnessAgent 实例。
 *
 * <p>
 * 与固定 YAML 配置的 Agent 不同，此工厂按请求参数动态创建 Agent。
 * 支持根据请求参数配置 RAG、记忆、执行参数等高级功能。
 * </p>
 *
 * <p>
 * V2.0 升级：从 AdvancedAgentFactory（594 行）精简为本文件（约 140 行）。
 * 删除 applyToolConfig() 和 applySkillConfig()，所有配置方法内联到 createTempAgent() 中。
 * </p>
 *
 * <p>
 * TODO: AgentScope 2.0 新 RAG/Memory 模块上线后，需将
 * {@link io.agentscope.core.rag.Knowledge}、{@code RetrieveConfig}、
 * {@code LongTermMemory} 迁移到新的 Middleware 注入方式。
 * </p>
 */
@Service
@SuppressWarnings("removal")
public class TempAgentFactory {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(TempAgentFactory.class);

    /** Agent 服务，用于获取基础 Agent 的模型和提示词 */
    private final AgentService agentService;

    /** 核心配置属性 */
    private final AgentscopeCoreProperties coreProperties;

    /** 知识库 Bean 映射，key 为知识库名称，value 为 Knowledge 实例 */
    private final Map<String, Knowledge> knowledgeBeans = new HashMap<>();

    /** 长期记忆 Bean 映射，key 为记忆名称，value 为 LongTermMemory 实例 */
    private final Map<String, io.agentscope.core.memory.LongTermMemory> memoryBeans = new HashMap<>();

    /** 默认检索配置，限制返回 5 条结果，最低相似度阈值 0.5 */
    private RetrieveConfig defaultRetrieveConfig;

    /**
     * 构造临时 Agent 工厂。
     *
     * @param agentService   Agent 服务
     * @param coreProperties 核心配置属性
     */
    public TempAgentFactory(AgentService agentService,
            AgentscopeCoreProperties coreProperties) {
        this.agentService = agentService;
        this.coreProperties = coreProperties;
        this.defaultRetrieveConfig = RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();
    }

    /**
     * 设置知识库 Bean 映射。
     *
     * @param knowledgeBeans 知识库 Bean 映射
     */
    public void setKnowledgeBeans(Map<String, Knowledge> knowledgeBeans) {
        if (knowledgeBeans != null)
            this.knowledgeBeans.putAll(knowledgeBeans);
    }

    /**
     * 设置长期记忆 Bean 映射。
     *
     * @param memoryBeans 长期记忆 Bean 映射
     */
    public void setMemoryBeans(Map<String, io.agentscope.core.memory.LongTermMemory> memoryBeans) {
        if (memoryBeans != null)
            this.memoryBeans.putAll(memoryBeans);
    }

    /**
     * 根据请求参数动态创建临时 Agent。
     *
     * <p>
     * 创建流程：
     * 1. 从 AgentService 获取基础 Agent 的模型和系统提示词
     * 2. 使用 V2.0 HarnessAgent.builder() 构建临时 Agent
     * 3. 根据 UnifiedChatRequest 中的参数应用 RAG、记忆、执行配置
     * 4. 构建并返回 Agent 实例
     * </p>
     *
     * @param baseAgentName 基础 Agent 名称，用于获取模型和提示词
     * @param request       统一聊天请求，包含动态配置参数
     * @return 动态创建的 Agent 实例，创建失败时返回 null
     */
    public Agent createTempAgent(String baseAgentName, UnifiedChatRequest request) {
        try {
            // 获取基础 Agent 的模型和提示词
            Model model = agentService.getAgentModel(baseAgentName);
            String sysPrompt = agentService.getAgentSysPrompt(baseAgentName);
            if (model == null) {
                log.error("基础 Agent [{}] 没有可用的模型", baseAgentName);
                return null;
            }

            // 使用 V2.0 HarnessAgent.builder() 构建
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name(baseAgentName + "-temp-" + System.currentTimeMillis())
                    .sysPrompt(sysPrompt != null ? sysPrompt : "")
                    .model(model)
                    .workspace(coreProperties.getWorkspaceBasePath() + "/" + baseAgentName)
                    .compaction(buildCompactionConfig());

            // 应用高级配置：RAG、记忆、执行参数
            applyRagConfig(request, builder);
            applyMemoryConfig(request, builder);
            applyExecutionConfig(request, builder);

            return builder.build();
        } catch (Exception e) {
            log.error("动态创建 Agent 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 应用 RAG（检索增强生成）配置。
     *
     * <p>
     * 根据请求中的 ragMode 参数配置 Agent 的 RAG 功能：
     * - NONE：不启用 RAG
     * - GENERIC：通用 RAG 模式，检索结果直接注入上下文
     * - AGENTIC：Agent RAG 模式，Agent 自主决定何时检索
     * 同时关联请求中指定的知识库。
     * </p>
     *
     * @param request 统一聊天请求
     * @param builder Agent Builder
     */
    private void applyRagConfig(UnifiedChatRequest request, HarnessAgent.Builder builder) {
        String ragMode = request.getRagMode();
        if (ragMode == null || "NONE".equals(ragMode))
            return;

        // V2.0: knowledge(), ragMode(), retrieveConfig() removed from HarnessAgent.Builder
        // RAG configuration must be provided via middleware instead
        log.warn("V2.0: RAG配置 (ragMode={}) 需要通过 Middleware 注入，Builder API 已移除", ragMode);
    }

    /**
     * 应用记忆配置。
     *
     * <p>
     * 根据请求中的 memoryMode 参数配置 Agent 的记忆功能：
     * - NONE：不启用记忆
     * - IN_MEMORY：内存记忆（HarnessAgent 默认内置）
     * - LONG_TERM：长期记忆，关联指定的 LongTermMemory 实例
     * </p>
     *
     * @param request 统一聊天请求
     * @param builder Agent Builder
     */
    private void applyMemoryConfig(UnifiedChatRequest request, HarnessAgent.Builder builder) {
        String memoryMode = request.getMemoryMode();
        if (memoryMode == null || "NONE".equals(memoryMode))
            return;

        if ("IN_MEMORY".equalsIgnoreCase(memoryMode))
            return; // HarnessAgent 默认内置内存记忆

        // V2.0: longTermMemory() and longTermMemoryMode() removed from HarnessAgent.Builder
        // Long-term memory must be configured via middleware instead
        if (request.getLongTermMemory() != null && !memoryBeans.isEmpty()) {
            log.warn("V2.0: 长期记忆 (name={}) 需要通过 Middleware 注入，Builder API 已移除",
                    request.getLongTermMemory());
        }
    }

    /**
     * 应用执行参数配置。
     *
     * <p>
     * 配置 Agent 的执行限制：
     * - maxIters：ReAct 循环最大迭代次数
     * - enableMetaTool：是否启用 MetaTool（Agent 自主管理工具）
     * </p>
     *
     * @param request 统一聊天请求
     * @param builder Agent Builder
     */
    private void applyExecutionConfig(UnifiedChatRequest request, HarnessAgent.Builder builder) {
        if (request.getMaxIters() != null)
            builder.maxIters(request.getMaxIters());
        if (request.getEnableMetaTool() != null)
            builder.enableMetaTool(request.getEnableMetaTool());
    }

    /**
     * 构建 Memory Compaction 配置。
     *
     * <p>
     * 从核心配置属性中读取 Compaction 参数，构建 CompactionConfig 实例。
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

    /**
     * 根据名称列表获取知识库实例集合。
     *
     * <p>
     * 从 knowledgeBeans 中查找匹配的知识库实例，
     * 不存在的名称会被静默跳过。
     * </p>
     *
     * @param knowledgeBaseNames 知识库名称列表
     * @return 匹配的 Knowledge 实例集合
     */
    private Set<Knowledge> getKnowledgeBases(List<String> knowledgeBaseNames) {
        if (knowledgeBaseNames == null || knowledgeBaseNames.isEmpty())
            return Collections.emptySet();
        Set<Knowledge> set = new HashSet<>();
        for (String name : knowledgeBaseNames) {
            if (knowledgeBeans.containsKey(name))
                set.add(knowledgeBeans.get(name));
        }
        return set;
    }
}
