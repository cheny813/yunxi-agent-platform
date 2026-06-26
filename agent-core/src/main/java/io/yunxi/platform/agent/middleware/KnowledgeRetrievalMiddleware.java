package io.yunxi.platform.agent.middleware;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import reactor.core.publisher.Flux;

/**
 * 知识检索 Middleware，将 RAG 检索结果注入 Agent 调用上下文。
 *
 * <p>
 * 这是 AgentScope V2.0-RC3 中连接 {@link Knowledge} 实例与 Agent 的标准方式。
 * 通过 {@link MiddlewareBase#onAgent(Agent, AgentInput, Function)} 拦截，
 * 调用 {@link Knowledge#retrieve(String, RetrieveConfig)} 完成检索。
 * </p>
 *
 * <p>
 * 注意：{@link Knowledge} 在 RC3 中标记为 {@code @Deprecated(forRemoval=true)}，
 * 框架暂未提供新 RAG API。待框架新 API 上线后迁移。
 * </p>
 *
 * <h3>工作模式</h3>
 * <ul>
 * <li><b>GENERIC</b> — 检索结果注入系统消息，LLM 在生成回答时参考</li>
 * <li><b>AGENTIC</b> — 检索结果注入用户消息，Agent 自主决定如何使用</li>
 * </ul>
 */
@SuppressWarnings("removal")
public class KnowledgeRetrievalMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalMiddleware.class);

    /** 知识库实例集合 */
    private final Collection<Knowledge> knowledgeBases;

    /** 检索配置 */
    private final RetrieveConfig retrieveConfig;

    /** RAG 工作模式 */
    private final String ragMode;

    /** 检索文档之间的分隔线 */
    private static final String DOC_SEPARATOR = "\n---\n";

    /** 通用检索（GENERIC）模式 */
    public static final String MODE_GENERIC = "GENERIC";

    /** Agent 自主检索（AGENTIC）模式 */
    public static final String MODE_AGENTIC = "AGENTIC";

    /**
     * 构造知识检索 Middleware。
     *
     * @param knowledgeBases 知识库实例集合（至少一个）
     * @param retrieveConfig 检索配置（limit、scoreThreshold）
     * @param ragMode        RAG 工作模式（GENERIC / AGENTIC）
     */
    public KnowledgeRetrievalMiddleware(Collection<Knowledge> knowledgeBases,
            RetrieveConfig retrieveConfig, String ragMode) {
        this.knowledgeBases = knowledgeBases;
        this.retrieveConfig = retrieveConfig;
        this.ragMode = ragMode;
    }

    // ==================== Middleware 入口 ====================

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        String query = extractQuery(input.msgs());
        if (query == null || query.isBlank()) {
            log.debug("Agent [{}] RAG: 无法提取查询文本，跳过检索", agentName(agent));
            return next.apply(input);
        }

        try {
            String context = retrieveKnowledge(query);
            if (context == null || context.isBlank()) {
                log.debug("Agent [{}] RAG: 知识库未检索到相关文档", agentName(agent));
                return next.apply(input);
            }

            List<Msg> augmentedMsgs = injectContext(input.msgs(), context);
            log.info("Agent [{}] RAG({}): 检索到 {} 条文档，已注入上下文",
                    agentName(agent), ragMode,
                    context.split(DOC_SEPARATOR).length);

            // V2.0-RC3: AgentInput 是 Java record，直接构造
            AgentInput wrappedInput = new AgentInput(augmentedMsgs);
            return next.apply(wrappedInput);
        } catch (Exception e) {
            log.error("Agent [{}] RAG 检索异常: {}", agentName(agent), e.getMessage());
            return next.apply(input);
        }
    }

    // ==================== 检索逻辑 ====================

    /**
     * 从所有知识库中检索相关文档。
     * <p>
     * V2.0-RC3: Knowledge.retrieve(String, RetrieveConfig) 返回 Mono<List<Document>>，
     * 通过 block() 同步等待结果。待框架新 RAG API 上线后迁移。
     * </p>
     */
    private String retrieveKnowledge(String query) {
        StringBuilder allResults = new StringBuilder();

        for (Knowledge kb : knowledgeBases) {
            try {
                RetrieveConfig config = retrieveConfig != null ? retrieveConfig
                        : RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();
                List<Document> docs = kb.retrieve(query, config).block();
                if (docs == null || docs.isEmpty())
                    continue;

                String formatted = formatRetrievalResults(docs);
                if (formatted != null && !formatted.isBlank()) {
                    if (allResults.length() > 0)
                        allResults.append(DOC_SEPARATOR);
                    allResults.append(formatted);
                }
            } catch (Exception e) {
                log.warn("知识库检索失败: {}, 错误: {}", kbName(kb), e.getMessage());
            }
        }

        return allResults.length() > 0 ? allResults.toString() : null;
    }

    /**
     * 将检索结果列表格式化为上文字符串。
     */
    private String formatRetrievalResults(List<Document> results) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (Document doc : results) {
            if (sb.length() > 0)
                sb.append(DOC_SEPARATOR);
            String text = (doc.getMetadata() != null && doc.getMetadata().getContentText() != null
                    && !doc.getMetadata().getContentText().isBlank())
                    ? doc.getMetadata().getContentText()
                    : doc.toString();
            sb.append("[文档 ").append(idx++).append("] ").append(text);
        }
        return sb.toString();
    }

    // ==================== 消息注入 ====================

    private String extractQuery(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty())
            return null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg != null) {
                String text = msg.getTextContent();
                if (text != null && !text.isBlank())
                    return text.trim();
            }
        }
        return null;
    }

    private List<Msg> injectContext(List<Msg> originalMsgs, String context) {
        List<Msg> augmented = new ArrayList<>();
        String prefix = MODE_AGENTIC.equalsIgnoreCase(ragMode)
                ? "以下是从知识库中检索到的相关信息，请在回答时参考：\n\n"
                : "【知识库参考内容】请基于以下知识库内容回答用户问题。如果知识库内容不足以回答问题，请诚实告知：\n\n";
        augmented.add(Msg.builder().textContent(prefix + context).build());
        augmented.addAll(originalMsgs);
        return augmented;
    }

    // ==================== 辅助 ====================

    private String agentName(Agent agent) {
        try {
            return agent.getName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String kbName(Knowledge kb) {
        try {
            return kb.getClass().getSimpleName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
