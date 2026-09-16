package io.yunxi.platform.agent.middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.platform.file.FileUploadService;
import io.yunxi.platform.file.dto.FileSearchRequest;
import io.yunxi.platform.file.dto.FileSearchResult;
import reactor.core.publisher.Flux;

/**
 * 文件上下文注入中间件。
 *
 * <p>按本次调用的检索词查找相似文件，命中时把文件内容作为上下文消息前置到输入之前，
 * 使模型在回答时能够引用已上传的资料。检索范围限定在调用方所属用户，单条内容超出长度
 * 上限时截断，避免上下文膨胀。</p>
 *
 * <p>检索为空或失败时保持输入不变，仅记录日志。是否检索由调用方按调用维度声明，见
 * {@link CallContextKeys#FILE_CONTEXT_ENABLED_KEY}。</p>
 *
 * @author yunxi-agent-platform
 */
public class FileContextInjectionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(FileContextInjectionMiddleware.class);

    /** 检索相似度阈值 */
    private static final double SIMILARITY_THRESHOLD = 0.7;
    /** 检索返回条数上限 */
    private static final int TOP_K = 3;
    /** 单个文件内容注入的最大长度 */
    private static final int MAX_CONTENT_LENGTH = 1000;

    private final FileUploadService fileUploadService;

    public FileContextInjectionMiddleware(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @Override
    public int order() {
        return 110;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (!CallContextKeys.fileContextEnabled(ctx)) {
            return next.apply(input);
        }
        List<Msg> msgs = input.msgs();
        if (msgs == null || msgs.isEmpty()) {
            return next.apply(input);
        }
        String userId = ctx != null ? ctx.getUserId() : null;
        if (userId == null || userId.isBlank()) {
            return next.apply(input);
        }
        String query = CallContextKeys.fileContextQuery(ctx, lastUserText(msgs));
        if (query == null || query.isBlank()) {
            return next.apply(input);
        }
        try {
            FileSearchRequest searchRequest = FileSearchRequest.builder()
                    .userId(userId)
                    .query(query)
                    .topK(TOP_K)
                    .threshold(SIMILARITY_THRESHOLD)
                    .includeContent(true)
                    .build();
            List<FileSearchResult> relevantFiles =
                    fileUploadService.searchRelevantFiles(searchRequest);
            if (relevantFiles == null || relevantFiles.isEmpty()) {
                return next.apply(input);
            }
            log.info("文件检索命中 {} 个: {}", relevantFiles.size(),
                    relevantFiles.stream()
                            .map(f -> f.getFileName() + "(" + String.format("%.2f", f.getSimilarity()) + ")")
                            .collect(Collectors.joining(", ")));
            List<Msg> enhanced = new ArrayList<>();
            enhanced.add(Msg.builder().textContent(buildFileContext(relevantFiles)).build());
            enhanced.addAll(msgs);
            return next.apply(new AgentInput(enhanced));
        } catch (Exception e) {
            log.warn("文件检索失败，忽略: {}", e.getMessage());
            return next.apply(input);
        }
    }

    /**
     * 取最后一条非空用户消息文本作为检索词回退值。
     */
    private static String lastUserText(List<Msg> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            String text = msg != null ? msg.getTextContent() : null;
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /**
     * 构建文件上下文文本。
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
