package io.yunxi.platform.agent.middleware;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.RetrieveConfig;
import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;

/**
 * 知识检索 Middleware，将 RAG 检索结果注入 Agent 调用上下文。
 *
 * <p>
 * 这是 AgentScope V2.0 中连接 {@link Knowledge} 实例与 Agent 的标准方式。
 * V2.0 移除了 {@code HarnessAgent.Builder.knowledge()} 方法，
 * RAG 配置必须通过 Middleware 注入。此 Middleware 复用框架已有的
 * {@link Knowledge#retrieve(String, RetrieveConfig)} 完成检索，
 * 不引入任何自定义检索逻辑。
 * </p>
 *
 * <h3>工作模式</h3>
 * <ul>
 * <li><b>GENERIC</b> — 检索结果注入系统消息，LLM 在生成回答时参考</li>
 * <li><b>AGENTIC</b> — 检索结果注入用户消息，Agent 自主决定如何使用</li>
 * </ul>
 *
 * <h3>框架复用</h3>
 * <ul>
 * <li>检索：直接调用 {@link Knowledge#retrieve(String, RetrieveConfig)}（框架核心 API）</li>
 * <li>注入：通过 {@link MiddlewareBase#onAgent(Agent, AgentInput, Function)} 拦截（V2.0 标准扩展点）</li>
 * <li>无自定义 NLP/RAG 管道，不重复造轮子</li>
 * </ul>
 *
 * <p>
 * TODO: AgentScope 2.0 新 RAG 模块上线后，替换 {@code Knowledge#retrieve()} 调用为新 API。
 * </p>
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

    /**
     * Agent 入口拦截：在 Agent 调用前执行知识检索并注入上下文。
     *
     * <p>
     * 流程：
     * 1. 从最后一条用户消息中提取查询文本
     * 2. 对每个知识库调用 {@link Knowledge#retrieve(String, RetrieveConfig)}
     * 3. 将检索结果格式化为上下文文本
     * 4. 注入到消息列表并传递给下游 Middleware
     * </p>
     *
     * @param agent 当前 Agent 实例
     * @param input Agent 输入，包含用户消息列表
     * @param next  下游 Middleware 链
     * @return 增强后的事件流
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, AgentInput input,
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

            AgentInput wrappedInput = createAgentInput(augmentedMsgs);
            return next.apply(wrappedInput);
        } catch (Exception e) {
            log.error("Agent [{}] RAG 检索异常: {}", agentName(agent), e.getMessage());
            // 检索失败时降级为直接透传，不阻断 Agent 调用
            return next.apply(input);
        }
    }

    // ==================== AgentInput 构造 ====================

    /**
     * 创建包含增强消息的 AgentInput 实例。
     *
     * <p>
     * AgentScope V2.0-RC1 中 {@code AgentInput} 是 class（非接口），
     * 可能为 Java record。通过其公开构造函数创建实例。
     * 若构造函数签名变化，尝试反射兜底。
     * </p>
     *
     * @param msgs 增强后的消息列表
     * @return AgentInput 实例
     */
    private static AgentInput createAgentInput(List<Msg> msgs) {
        try {
            // 尝试直接构造（record 有规范构造函数）
            Constructor<AgentInput> ctor = AgentInput.class.getDeclaredConstructor(List.class);
            ctor.setAccessible(true);
            return ctor.newInstance(msgs);
        } catch (Exception e) {
            throw new RuntimeException("无法构造 AgentInput 实例: " + e.getMessage(), e);
        }
    }

    // ==================== 检索逻辑 ====================

    /**
     * 从所有知识库中检索相关文档。
     *
     * <p>
     * 通过反射调用 {@code Knowledge.retrieve(String, RetrieveConfig)}，
     * 兼容 AgentScope V2.0-RC1 中标记为 {@code @Deprecated(forRemoval=true)} 的
     * Knowledge 接口。各 Knowledge 实现类（SimpleKnowledge、
     * RAGFlowKnowledge 等）的 retrieve 逻辑完全由框架提供，此处仅做薄封装。
     * </p>
     *
     * @param query 用户查询
     * @return 格式化的检索上下文文本，无结果时返回 null
     */
    private String retrieveKnowledge(String query) {
        StringBuilder allResults = new StringBuilder();

        for (Knowledge kb : knowledgeBases) {
            try {
                String result = retrieveFrom(kb, query);
                if (result != null && !result.isBlank()) {
                    if (allResults.length() > 0) {
                        allResults.append(DOC_SEPARATOR);
                    }
                    allResults.append(result);
                }
            } catch (Exception e) {
                log.warn("知识库检索失败: {}, 错误: {}", kbName(kb), e.getMessage());
            }
        }

        return allResults.length() > 0 ? allResults.toString() : null;
    }

    /**
     * 从单个知识库检索并格式化结果。
     *
     * <p>
     * 使用反射调用框架的 {@code Knowledge.retrieve()} 方法，
     * 与 {@code KnowledgeCreator} 体系一致，确保兼容所有知识库类型。
     * </p>
     *
     * @param kb    知识库实例
     * @param query 用户查询
     * @return 格式化文本，失败或无结果时返回 null
     */
    private String retrieveFrom(Knowledge kb, String query) {
        try {
            // 通过反射调用 retrieve 方法，兼容不同 SDK 版本的方法签名
            RetrieveConfig config = retrieveConfig != null ? retrieveConfig
                    : RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

            List<?> results = invokeRetrieve(kb, query, config);
            if (results == null || results.isEmpty()) {
                return null;
            }

            return formatRetrievalResults(results);
        } catch (Exception e) {
            log.warn("从知识库检索失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 通过反射调用 Knowledge.retrieve() 方法。
     *
     * <p>
     * 尝试多种方法签名以兼容不同 SDK 版本：
     * <ol>
     * <li>{@code retrieve(String, RetrieveConfig)} — V2.0-RC1 标准签名</li>
     * <li>{@code retrieve(String)} — 旧版签名（无 RetrieveConfig 参数）</li>
     * </ol>
     * </p>
     *
     * @param kb     知识库实例
     * @param query  查询文本
     * @param config 检索配置
     * @return 检索结果列表
     */
    private List<?> invokeRetrieve(Knowledge kb, String query, RetrieveConfig config) {
        try {
            Method method = kb.getClass().getMethod("retrieve", String.class, RetrieveConfig.class);
            return (List<?>) method.invoke(kb, query, config);
        } catch (NoSuchMethodException e) {
            log.debug("retrieve(String, RetrieveConfig) 不可用，尝试 retrieve(String)");
        } catch (Exception e) {
            throw new RuntimeException("调用 retrieve 失败", e);
        }

        try {
            Method method = kb.getClass().getMethod("retrieve", String.class);
            return (List<?>) method.invoke(kb, query);
        } catch (Exception e) {
            throw new RuntimeException("无法调用 retrieve 方法", e);
        }
    }

    /**
     * 将检索结果列表格式化为上下文字符串。
     *
     * <p>
     * 对每个检索结果尝试通过反射提取文本内容：
     * 优先查找 {@code getContent()} / {@code getText()} / {@code getTitle()} 方法，
     * 找不到则使用 {@code toString()} 作为兜底。
     * </p>
     *
     * @param results 检索结果列表
     * @return 格式化的上下文字符串
     */
    private String formatRetrievalResults(List<?> results) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;

        for (Object doc : results) {
            if (sb.length() > 0) {
                sb.append(DOC_SEPARATOR);
            }
            String text = extractDocText(doc);
            sb.append("[文档 ").append(idx++).append("] ").append(text);
        }

        return sb.toString();
    }

    /**
     * 从检索结果对象中提取文本内容。
     */
    private String extractDocText(Object doc) {
        if (doc == null) return "(空文档)";
        if (doc instanceof String) return (String) doc;

        // 尝试常见的文档内容访问方法
        for (String methodName : new String[] { "getContent", "getText", "text", "content" }) {
            try {
                Method m = doc.getClass().getMethod(methodName);
                Object result = m.invoke(doc);
                if (result instanceof String && !((String) result).isBlank()) {
                    return (String) result;
                }
            } catch (Exception ignored) {
                // 方法不存在，尝试下一个
            }
        }

        return doc.toString();
    }

    // ==================== 消息注入 ====================

    /**
     * 从消息列表中提取用户查询文本。
     *
     * <p>
     * 取最后一条非空的文本消息作为查询，覆盖多轮对话中
     * 用户最新输入的场景。
     * </p>
     *
     * @param msgs 消息列表
     * @return 查询文本，无法提取时返回 null
     */
    private String extractQuery(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) return null;

        // 从后往前遍历，取第一条非空文本
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

    /**
     * 将检索上下文注入消息列表。
     *
     * <p>
     * GENERIC 模式：在用户消息前插入系统级上下文消息
     * AGENTIC 模式：在用户消息前插入带检索指令的上下文消息
     * </p>
     *
     * @param originalMsgs 原始消息列表
     * @param context      检索到的知识上下文
     * @return 增强后的消息列表
     */
    private List<Msg> injectContext(List<Msg> originalMsgs, String context) {
        List<Msg> augmented = new ArrayList<>();

        String prefix = MODE_AGENTIC.equalsIgnoreCase(ragMode)
                ? "以下是从知识库中检索到的相关信息，请在回答时参考：\n\n"
                : "【知识库参考内容】请基于以下知识库内容回答用户问题。如果知识库内容不足以回答问题，请诚实告知：\n\n";

        Msg contextMsg = Msg.builder()
                .textContent(prefix + context)
                .build();

        augmented.add(contextMsg);
        augmented.addAll(originalMsgs);

        return augmented;
    }

    // ==================== 辅助 ====================

    private String agentName(Agent agent) {
        try { return agent.getName(); } catch (Exception e) { return "unknown"; }
    }

    private String kbName(Knowledge kb) {
        try { return kb.getClass().getSimpleName(); } catch (Exception e) { return "unknown"; }
    }
}
