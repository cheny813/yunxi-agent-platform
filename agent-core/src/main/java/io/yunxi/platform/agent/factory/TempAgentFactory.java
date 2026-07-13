package io.yunxi.platform.agent.factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.rag.ApplicationRAG;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;

/**
 * 临时 Agent 工厂，创建支持高级功能的 HarnessAgent 实例。
 *
 * <p>与固定 YAML 配置的 Agent 不同，此工厂按请求参数动态创建 Agent。
 * 支持根据请求参数配置 RAG、执行参数等高级功能。</p>
 *
 * <p>RAG 不再依赖已废弃（forRemoval）的
 * {@code io.agentscope.core.rag.Knowledge} API，改由 {@link ApplicationRAG}
 * 应用层中间件（基于文件向量检索后端）注入检索上下文；
 * 原长程记忆（LongTermMemory）的 Middleware 注入尚待框架新 Memory API，
 * 此处不再强行桥接废弃类型。</p>
 */
@Service
public class TempAgentFactory {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(TempAgentFactory.class);

    /** Agent 服务，用于获取基础 Agent 的模型和提示词 */
    private final AgentService agentService;

    /** 核心配置属性 */
    private final AgentscopeCoreProperties coreProperties;

    /** 应用层 RAG 中间件工厂 */
    private final ApplicationRAG applicationRAG;

    /**
     * 构造临时 Agent 工厂。
     *
     * @param agentService     Agent 服务
     * @param coreProperties   核心配置属性
     * @param applicationRAG   应用层 RAG 中间件工厂
     */
    public TempAgentFactory(AgentService agentService,
            AgentscopeCoreProperties coreProperties,
            ApplicationRAG applicationRAG) {
        this.agentService = agentService;
        this.coreProperties = coreProperties;
        this.applicationRAG = applicationRAG;
    }

    /**
     * 根据请求参数动态创建临时 Agent。
     *
     * <p>创建流程：
     * 1. 从 AgentService 获取基础 Agent 的模型和系统提示词
     * 2. 使用 HarnessAgent.builder() 构建临时 Agent
     * 3. 根据 UnifiedChatRequest 中的参数应用 RAG、执行配置
     * 4. 构建并返回 Agent 实例</p>
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

            // 使用 HarnessAgent.builder() 构建
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name(baseAgentName + "-temp-" + System.currentTimeMillis())
                    .sysPrompt(sysPrompt != null ? sysPrompt : "")
                    .model(model)
                    .workspace(coreProperties.getWorkspaceBasePath() + "/agents/" + baseAgentName)
                    .compaction(buildCompactionConfig());

            // 应用高级配置：RAG、执行参数
            applyRagConfig(request, builder);
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
     * <p>根据请求中的 ragMode 参数，通过 {@link ApplicationRAG} 生成应用层 RAG 中间件注入检索能力：
     * - NONE：不启用 RAG
     * - GENERIC / AGENTIC：基于用户文件向量检索注入上下文</p>
     *
     * @param request 统一聊天请求
     * @param builder Agent Builder
     */
    private void applyRagConfig(UnifiedChatRequest request, HarnessAgent.Builder builder) {
        String ragMode = request.getRagMode();
        if (ragMode == null || "NONE".equalsIgnoreCase(ragMode)) {
            return;
        }
        builder.middleware(applicationRAG.createMiddleware(ragMode, request.getUserId()));
        log.info("RAG({}): 已注册 ApplicationRAG 中间件 (userId={})", ragMode, request.getUserId());
    }

    /**
     * 应用执行参数配置。
     *
     * <p>配置 Agent 的执行限制：
     * - maxIters：ReAct 循环最大迭代次数
     * - enableMetaTool：是否启用 MetaTool（Agent 自主管理工具）</p>
     *
     * @param request 统一聊天请求
     * @param builder Agent Builder
     */
    private void applyExecutionConfig(UnifiedChatRequest request, HarnessAgent.Builder builder) {
        if (request.getMaxIters() != null) {
            builder.maxIters(request.getMaxIters());
        }
        if (request.getEnableMetaTool() != null) {
            builder.enableMetaTool(request.getEnableMetaTool());
        }
    }

    /**
     * 构建 Memory Compaction 配置。
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
}
