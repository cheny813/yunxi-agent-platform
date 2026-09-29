package io.yunxi.platform.execution.interceptors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.ExecutionInterceptor;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;

/**
 * 文件检索启用条件拦截器（order=300）。
 *
 * <p>判断本次调用是否应当启用文件检索：需同时满足配置开关打开、会话型入口、记忆模式非关闭、
 * 且请求要求携带历史。满足时把检索词写入执行上下文，由调用入口的请求级中间件执行检索动作。</p>
 *
 * <p>与 Agent 级全量注入的 {@code ApplicationRAG} 不重复：后者覆盖 Agent 级静态知识注入，
 * 本处仅处理会话型的按需文件检索。</p>
 *
 * <p>可选按配置启停：{@code agentscope.core.interceptor.rag-enabled}（默认 true）。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class RagRetrievalInterceptor implements ExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalInterceptor.class);

    /** 检索词在执行上下文属性中的键 */
    public static final String ATTR_RAG_QUERY = "ragQuery";

    private final AgentscopeCoreProperties properties;

    public RagRetrievalInterceptor(AgentscopeCoreProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return 300;
    }

    @Override
    public void preHandle(ExecutionContext ctx) {
        if (!properties.getInterceptor().isRagEnabled()) {
            return;
        }
        if (!ragApplicable(ctx)) {
            return;
        }
        ctx.getAttributes().put(ATTR_RAG_QUERY, ctx.getRequest().getMessage());
        log.debug("文件检索已启用: agentName={}", ctx.getAgentName());
    }

    /**
     * 检索启用前置条件：会话型 + 智能记忆 + 含历史。
     */
    private boolean ragApplicable(ExecutionContext ctx) {
        MemoryConfig memoryConfig = ctx.getRequest().getMemoryConfig();
        ConversationEntity conversation = ctx.getConversation();
        return conversation != null && memoryConfig != null && !memoryConfig.isNone()
                && ctx.getRequest().isIncludeHistory();
    }
}
