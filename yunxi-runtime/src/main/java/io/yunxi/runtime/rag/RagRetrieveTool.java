package io.yunxi.runtime.rag;

import io.agentscope.core.agent.RuntimeContext;
import io.yunxi.runtime.tenant.TenantContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RAG 检索工具（agentic RAG 入口）。
 *
 * <p>经 AgentScope-Java {@code @Tool} 暴露为 {@code rag_retrieve}，由 {@link RagToolRegistrar} 注入
 * 每个 Agent 的 toolkit 的 {@code rag} 工具组（仿 MUSE 元工具范式）。Agent 自决是否调用、检索哪个集合，
 * 不实现框架已 deprecated 的 {@code Knowledge} 接口，检索直接基于 {@code DocumentChunkStore}。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.rag.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(DocumentChunkStore.class)
public class RagRetrieveTool {

    private static final Logger log = LoggerFactory.getLogger(RagRetrieveTool.class);

    private final DocumentChunkStore store;
    @Value("${yunxi.rag.default-top-k:5}")
    private int defaultTopK = 5;
    private final RagProperties ragProperties;

    public RagRetrieveTool(DocumentChunkStore store) {
        this(store, null);
    }

    @Autowired
    public RagRetrieveTool(DocumentChunkStore store, RagProperties ragProperties) {
        this.store = Objects.requireNonNull(store, "DocumentChunkStore 未装配，RAG 检索工具不可用");
        this.ragProperties = ragProperties;
    }

    @Tool(name = "rag_retrieve",
            description = "从企业/个人知识库检索与 query 相关的文档分块。collection 为知识库集合名；"
                    + "query 为检索问句；top_k 为返回条数（缺省 5）。返回按相关性排序的分块正文列表。"
                    + "仅当任务需要外部知识时调用，检索结果作为回答的事实依据。")
    public String retrieve(
            @ToolParam(name = "collection", description = "知识库集合名（命名空间）") String collection,
            @ToolParam(name = "query", description = "检索问句") String query,
            @ToolParam(name = "top_k", required = false,
                    description = "返回条数，缺省 5") Integer topK,
            RuntimeContext runtimeContext) {
        Objects.requireNonNull(collection, "collection 不可为空");
        Objects.requireNonNull(query, "query 不可为空");
        // 平台级 RAG 集合白名单（简单版权限回读）：非空时仅允许访问清单内集合，拒绝其余，防越权读未授权知识库。
        List<String> allowed = (ragProperties != null) ? ragProperties.getAllowedCollections() : null;
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(collection)) {
            log.warn("[RAG] rag_retrieve 被拒：集合 {} 不在平台 RAG 白名单 {} 内", collection, allowed);
            return "⛔ 无权访问知识库集合「" + collection + "」：该集合不在本平台 RAG 允许访问清单内。请仅检索已授权的集合。";
        }
        int k = (topK == null || topK <= 0) ? defaultTopK : topK;
        List<DocumentChunk> chunks;
        try {
            chunks = store.findRelevant(currentTenant(), collection, resolveUserId(runtimeContext), query, k);
        } catch (RuntimeException ex) {
            // 仅向量模式：向量索引不可用时给出明确提示，不做关键词静默回退。
            log.warn("[RAG] 检索失败: {}", ex.getMessage());
            return "知识库检索当前不可用（向量检索服务未就绪），请稍后重试或联系管理员。";
        }
        if (chunks.isEmpty()) {
            return "未检索到与查询相关的知识库内容。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append("【分块 ").append(i + 1).append("】\n").append(chunks.get(i).content()).append("\n\n");
        }
        return sb.toString().strip();
    }

    /**
     * 检索按用户隔离：优先从框架注入的 {@link RuntimeContext} 取真实 userId（与四协议身份注入同源，
     * 由 AgentScope-Java 在工具调用期注入 per-request 上下文），缺失时回退匿名用户（单实例 / 单租户场景）。
     */
    private String resolveUserId(RuntimeContext runtimeContext) {
        if (runtimeContext != null && runtimeContext.getUserId() != null
                && !runtimeContext.getUserId().isBlank()) {
            return runtimeContext.getUserId();
        }
        return "anonymous";
    }

    /**
     * 检索按租户隔离：从平台租户上下文取当前租户（与四协议身份注入同源，由租户解析器在请求期绑定），
     * 缺失时回退 null（单实例 / 单租户场景），检索严格按 tenant 过滤。
     */
    private String currentTenant() {
        return TenantContext.current();
    }
}
