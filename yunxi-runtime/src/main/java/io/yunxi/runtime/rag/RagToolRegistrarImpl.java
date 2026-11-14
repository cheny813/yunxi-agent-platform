package io.yunxi.runtime.rag;

import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RAG 检索工具注册器实现：把 {@link RagRetrieveTool} 挂入 toolkit 的 {@code rag} 工具组。
 *
 * <p>仅当 {@code yunxi.rag.enabled=true} 时存在实现；未启用则列表为空，{@code AgentConfigurer} 跳过，
 * 对未启用 RAG 的 Agent 零侵入。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.rag.enabled", havingValue = "true", matchIfMissing = true)
public class RagToolRegistrarImpl implements RagToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RagToolRegistrarImpl.class);

    private final ObjectProvider<RagRetrieveTool> retrieveToolProvider;

    public RagToolRegistrarImpl(ObjectProvider<RagRetrieveTool> retrieveToolProvider) {
        this.retrieveToolProvider = retrieveToolProvider;
    }

    @Override
    public void registerTools(Toolkit toolkit) {
        RagRetrieveTool retrieveTool = retrieveToolProvider.getIfAvailable();
        if (retrieveTool == null) {
            log.warn("[rag] 检索工具未就绪，跳过 toolkit 注册");
            return;
        }
        toolkit.createToolGroup("rag", "RAG 知识库检索工具", true);
        toolkit.registration().tool(retrieveTool).group("rag").apply();
        log.info("[rag] 已注册 RAG 检索工具组: rag_retrieve");
    }
}
