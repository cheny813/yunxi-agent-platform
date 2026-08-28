package io.yunxi.platform.execution.interceptors;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.ExecutionInterceptor;
import io.yunxi.platform.file.FileUploadService;
import io.yunxi.platform.file.dto.FileSearchRequest;
import io.yunxi.platform.file.dto.FileSearchResult;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;

/**
 * 请求级文件 RAG 检索拦截器（order=300）。
 *
 * <p>将文件 RAG 注入提升为流式/阻塞两条通道统一执行的请求级能力。
 * 与 Agent 级 {@code ApplicationRAG} 中间件（onAgent 全量注入）并行不重复：
 * 本拦截器仅会话型 + 智能记忆时按需检索注入。</p>
 *
 * <p>可选按配置启停：{@code agentscope.core.interceptor.rag-enabled}（默认 true，
 * 保持既有会话型 + 智能记忆的文件检索注入行为）。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class RagRetrievalInterceptor implements ExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalInterceptor.class);

    /** 检索相似度阈值 */
    private static final double SIMILARITY_THRESHOLD = 0.7;
    /** 检索 TopK */
    private static final int TOP_K = 3;
    /** 单个文件内容最大注入长度 */
    private static final int MAX_CONTENT_LENGTH = 1000;

    private final FileUploadService fileUploadService;
    private final AgentscopeCoreProperties properties;

    public RagRetrievalInterceptor(FileUploadService fileUploadService,
                                   AgentscopeCoreProperties properties) {
        this.fileUploadService = fileUploadService;
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
        List<Msg> messages = ctx.getInputMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (!ragApplicable(ctx)) {
            return;
        }
        try {
            String ragUserId = ctx.getConversation().getUserId() != null
                    ? ctx.getConversation().getUserId() : "default";
            FileSearchRequest searchRequest = FileSearchRequest.builder()
                    .userId(ragUserId)
                    .query(ctx.getRequest().getMessage())
                    .topK(TOP_K)
                    .threshold(SIMILARITY_THRESHOLD)
                    .includeContent(true)
                    .build();
            List<FileSearchResult> relevantFiles = fileUploadService.searchRelevantFiles(searchRequest);
            if (relevantFiles == null || relevantFiles.isEmpty()) {
                return;
            }
            log.info("RAG检索到 {} 个相关文件: {}", relevantFiles.size(),
                    relevantFiles.stream()
                            .map(f -> f.getFileName() + "(" + String.format("%.2f", f.getSimilarity()) + ")")
                            .collect(Collectors.joining(", ")));
            Msg ragContextMsg = Msg.builder().textContent(buildFileContext(relevantFiles)).build();
            List<Msg> enhanced = new ArrayList<>();
            enhanced.add(ragContextMsg);
            enhanced.addAll(messages);
            ctx.setInputMessages(enhanced);
        } catch (Exception e) {
            log.warn("文件 RAG 检索失败，忽略: {}", e.getMessage());
        }
    }

    /**
     * RAG 注入前置条件：会话型 + 智能记忆 + 含历史。
     */
    private boolean ragApplicable(ExecutionContext ctx) {
        MemoryConfig memoryConfig = ctx.getRequest().getMemoryConfig();
        ConversationEntity conversation = ctx.getConversation();
        return conversation != null && memoryConfig != null && !memoryConfig.isNone()
                && ctx.getRequest().isIncludeHistory();
    }

    /**
     * 构建 RAG 文件上下文。
     */
    private static String buildFileContext(List<FileSearchResult> relevantFiles) {
        StringBuilder context = new StringBuilder();
        context.append("[相关文件上下文]\n\n");
        for (FileSearchResult file : relevantFiles) {
            context.append(String.format("文件: %s (相似度: %.2f%%)\n",
                    file.getFileName(), file.getSimilarity() * 100));
            context.append(String.format("类型: %s\n", file.getFileType().getDescription()));
            if (file.getContent() != null && !file.getContent().isEmpty()) {
                String content = file.getContent();
                if (content.length() > MAX_CONTENT_LENGTH) {
                    content = content.substring(0, MAX_CONTENT_LENGTH) + "...(内容已截断)";
                }
                context.append("内容:\n").append(content).append("\n");
            }
            context.append("---\n\n");
        }
        context.append("[请基于以上文件内容回答用户问题]\n");
        return context.toString();
    }
}
