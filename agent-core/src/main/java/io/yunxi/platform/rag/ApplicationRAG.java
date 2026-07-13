package io.yunxi.platform.rag;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.file.FileVectorService;
import io.yunxi.platform.file.dto.FileSearchRequest;
import io.yunxi.platform.file.dto.FileSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 应用层 RAG（检索增强生成）中间件。
 *
 * <p>原 {@code KnowledgeRetrievalMiddleware} 依赖已废弃（forRemoval）的
 * {@code io.agentscope.core.rag.Knowledge} API。本类改用平台既有的文件级向量检索后端
 * {@link FileVectorService}（Milvus + EmbeddingService）实现应用层 RAG，通过 GA 原生
 * {@link MiddlewareBase#onAgent} 钩子将检索到的文件上下文注入用户消息，完全脱离框架废弃的 rag 包。</p>
 *
 * <p>检索基于用户查询文本（从输入消息中提取），按 userId 隔离，与 {@code ChatAppService}
 * 共用同一条文件检索链路。Milvus 未启用时 {@link FileVectorService} 不可用，中间件自动跳过，
 * 不影响 Agent 正常对话。</p>
 *
 * <h3>工作模式</h3>
 * <ul>
 *   <li>GENERIC — 检索结果注入系统提示前缀，LLM 在生成回答时参考</li>
 *   <li>AGENTIC — 检索结果注入用户消息，Agent 自主决定如何使用</li>
 * </ul>
 */
@Component
public class ApplicationRAG {

    private static final Logger log = LoggerFactory.getLogger(ApplicationRAG.class);

    /** RAG 工作模式：通用检索（结果注入系统提示前缀） */
    public static final String MODE_GENERIC = "GENERIC";
    /** RAG 工作模式：Agent 自主检索（结果注入用户消息） */
    public static final String MODE_AGENTIC = "AGENTIC";

    /** 检索文档之间的分隔线 */
    private static final String DOC_SEPARATOR = "\n---\n";

    /** 文件向量检索服务（Milvus 未启用时为 null，中间件自动降级） */
    private final ObjectProvider<FileVectorService> fileVectorServiceProvider;

    /**
     * 构造应用层 RAG 中间件工厂。
     *
     * @param fileVectorServiceProvider 文件向量检索服务（可选注入，Milvus 未启用时取不到）
     */
    public ApplicationRAG(ObjectProvider<FileVectorService> fileVectorServiceProvider) {
        this.fileVectorServiceProvider = fileVectorServiceProvider;
    }

    /**
     * 为指定用户与 RAG 模式创建一个应用层 RAG 中间件。
     *
     * @param ragMode 工作模式（GENERIC / AGENTIC / 其他视为不启用）
     * @param userId  用户 ID，用于文件检索隔离
     * @return 中间件实例；ragMode 为空或 NONE 时返回透传中间件（不做任何注入）
     */
    public MiddlewareBase createMiddleware(String ragMode, String userId) {
        if (ragMode == null || "NONE".equalsIgnoreCase(ragMode)) {
            return passthrough();
        }
        // 捕获 ragMode 供内部匿名类使用（effectively final）
        final String mode = ragMode;
        return new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                    Function<AgentInput, Flux<AgentEvent>> next) {
                FileVectorService fv = fileVectorServiceProvider.getIfAvailable();
                if (fv == null) {
                    return next.apply(input);
                }
                String query = extractQuery(input.msgs());
                if (query == null || query.isBlank()) {
                    return next.apply(input);
                }
                try {
                    List<FileSearchResult> docs = fv.searchRelevantFiles(FileSearchRequest.builder()
                            .userId(userId)
                            .query(query)
                            .topK(5)
                            .build());
                    if (docs == null || docs.isEmpty()) {
                        return next.apply(input);
                    }
                    String context = format(docs);
                    List<Msg> augmented = injectContext(input.msgs(), context, mode);
                    log.info("ApplicationRAG({}): 检索到 {} 条文档并注入上下文",
                            mode, context.split(DOC_SEPARATOR).length);
                    return next.apply(new AgentInput(augmented));
                } catch (Exception e) {
                    log.warn("ApplicationRAG({}) 检索异常，跳过注入: {}", mode, e.getMessage());
                    return next.apply(input);
                }
            }
        };
    }

    /**
     * 透传中间件：不做任何检索注入，直接放行。
     */
    private MiddlewareBase passthrough() {
        return new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                    Function<AgentInput, Flux<AgentEvent>> next) {
                return next.apply(input);
            }
        };
    }

    /**
     * 将检索结果列表格式化为上文字符串。
     */
    private String format(List<FileSearchResult> docs) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (FileSearchResult doc : docs) {
            String text = (doc.getContent() != null && !doc.getContent().isBlank())
                    ? doc.getContent()
                    : doc.getMatchedContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(DOC_SEPARATOR);
            }
            sb.append("[文档 ").append(idx++).append("] ").append(text);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 将检索上下文注入消息列表头部。
     *
     * @param originalMsgs 原始消息列表
     * @param context      检索到的上文字符串
     * @param mode         RAG 工作模式（决定提示前缀风格）
     * @return 注入后的新消息列表
     */
    private List<Msg> injectContext(List<Msg> originalMsgs, String context, String mode) {
        List<Msg> augmented = new ArrayList<>();
        String prefix = MODE_AGENTIC.equalsIgnoreCase(mode)
                ? "以下是从知识库中检索到的相关信息，请在回答时参考：\n\n"
                : "【知识库参考内容】请基于以下知识库内容回答用户问题。如果知识库内容不足以回答问题，请诚实告知：\n\n";
        augmented.add(Msg.builder().textContent(prefix + context).build());
        augmented.addAll(originalMsgs);
        return augmented;
    }

    /**
     * 从消息列表中提取最新的非空查询文本（从后往前找）。
     */
    private String extractQuery(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return null;
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg != null) {
                String text = msg.getTextContent();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }
}
