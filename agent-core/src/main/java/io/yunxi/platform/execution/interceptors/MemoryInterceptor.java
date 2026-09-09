package io.yunxi.platform.execution.interceptors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.execution.spi.ExecutionInterceptor;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import io.yunxi.platform.shared.dto.ConfirmResultRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 拦截器 150：记忆模式分支与上下文注入。
 *
 * <p>职责：根据 {@link MemoryConfig} 决定 {@code inputMessages} 的组装方式：
 * <ul>
 *   <li>{@code isNone()}：无记忆模式，仅单条用户消息；</li>
 *   <li>否则：历史消息 + 当前用户消息（带异常降级为简单模式）；</li>
 *   <li>若请求携带 {@code contextData}，将页面上下文注入用户消息。</li>
 * </ul>
 * 注意：RAG 检索与注入不在本拦截器内完成（避免职责重复）——
 * Agent 级全量注入由 {@code ApplicationRAG} 中间件（onAgent 钩子）承担；
 * 会话型 + 智能记忆的按需文件检索由 {@code RagRetrievalInterceptor}（order=300）
 * 在拦截器链阶段完成（流式/阻塞两通道统一）。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class MemoryInterceptor implements ExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MemoryInterceptor.class);

    @Override
    public int getOrder() {
        return 150;
    }

    @Override
    public void preHandle(ExecutionContext ctx) {
        ExecutionRequest req = ctx.getRequest();
        String message = req.getMessage();
        Map<String, Object> contextData = req.getContextData();
        MemoryConfig memoryConfig = req.getMemoryConfig();

        // 1. 构建基础用户消息（含 contextData 页面上下文注入）
        Msg userMsg = buildUserMessage(message, contextData);
        // 1.5 注入人机确认（HITL）回传结果：确认结果本质是输入消息的有机组成部分，
        //     与记忆消息构建一并处理，避免单独维护一条仅做 DTO 转换的拦截器。
        userMsg = enrichWithConfirmResults(userMsg, req.getConfirmResults(), ctx);

        // 2. 根据记忆模式组装 inputMessages
        List<Msg> allMessages;
        if (ctx.getRequest().isQuickMode()) {
            // QUICK 模式：仅单条用户消息，不组装历史
            allMessages = new ArrayList<>();
            allMessages.add(userMsg);
            log.debug("Memory(QUICK): agentName={}", ctx.getAgentName());
        } else if (memoryConfig == null || memoryConfig.isNone() || !req.isIncludeHistory()) {
            allMessages = new ArrayList<>();
            allMessages.add(userMsg);
            log.debug("Memory(NONE): agentName={}, includeHistory={}", ctx.getAgentName(), req.isIncludeHistory());
        } else {
            try {
                List<Msg> historyMessages = req.getHistoryMessages();
                if (historyMessages == null) {
                    historyMessages = new ArrayList<>();
                }
                allMessages = new ArrayList<>(historyMessages);
                allMessages.add(userMsg);
                log.debug("Memory(SMART): historyCount={}, contextCount={}, mode={}",
                        historyMessages.size(), allMessages.size(), memoryConfig.getMemoryMode());
            } catch (Exception e) {
                log.error("Memory 智能记忆失败，降级为简单模式: {}", ctx.getAgentName(), e);
                allMessages = new ArrayList<>();
                allMessages.add(userMsg);
            }
        }

        ctx.setInputMessage(userMsg);
        ctx.setInputMessages(allMessages);
    }

    /**
     * 构建用户消息（含上下文数据注入）。
     */
    private Msg buildUserMessage(String message, Map<String, Object> contextData) {
        String finalMessage = message;
        if (contextData != null && !contextData.isEmpty()) {
            try {
                String contextStr = formatContextData(contextData);
                if (contextStr != null && !contextStr.isEmpty()) {
                    finalMessage = contextStr + "\n\n用户问题: " + message;
                }
            } catch (Exception e) {
                log.warn("上下文数据格式化失败，使用原始消息", e);
            }
        }
        return Msg.builder().textContent(finalMessage).build();
    }

    /**
     * 将请求携带的人机确认（HITL）回传结果转换为 AgentScope 的 {@link ConfirmResult}，
     * 注入输入消息的 {@code Msg.METADATA_CONFIRM_RESULTS} 元数据；无确认结果时原样返回。
     *
     * <p>原由独立 {@code HITLConfirmInterceptor} 承担，因确认结果是输入消息的有机组成部分，
     * 现合并到记忆消息构建阶段，减少一条仅做参数转换的拦截器。后续的校验（是否对应处于
     * ASKING 状态的工具调用）、执行或拒绝全部由 AgentScope 完成，yunxi 不实现确认语义。</p>
     */
    private Msg enrichWithConfirmResults(Msg userMsg, List<ConfirmResultRequest> requests, ExecutionContext ctx) {
        if (requests == null || requests.isEmpty()) {
            return userMsg;
        }
        List<ConfirmResult> confirmResults = new ArrayList<>();
        for (ConfirmResultRequest req : requests) {
            if (req == null || req.getToolCallId() == null || req.getToolCallId().isBlank()) {
                log.warn("确认结果缺少 toolCallId，已跳过: {}", req);
                continue;
            }
            ToolUseBlock toolCall = new ToolUseBlock(req.getToolCallId(), req.getToolName(), req.getInput());
            confirmResults.add(new ConfirmResult(req.isApproved(), toolCall));
        }
        if (confirmResults.isEmpty()) {
            return userMsg;
        }
        Map<String, Object> metadata = new HashMap<>();
        if (userMsg.getMetadata() != null) {
            metadata.putAll(userMsg.getMetadata());
        }
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
        Msg enriched = Msg.builder()
                .id(userMsg.getId())
                .name(userMsg.getName())
                .role(userMsg.getRole())
                .content(userMsg.getContent())
                .metadata(metadata)
                .build();
        int approved = 0;
        for (ConfirmResult r : confirmResults) {
            if (r.isConfirmed()) {
                approved++;
            }
        }
        log.info("[HITL] 已注入人机确认结果: agentName={}, conversationId={}, 批准={}, 拒绝={}",
                ctx.getAgentName(), ctx.getConversationId(), approved, confirmResults.size() - approved);
        return enriched;
    }

    /**
     * 通用上下文格式化（跳过 pageType，保留 configSummary/formData/其他）。
     */
    private String formatContextData(Map<String, Object> contextData) {
        StringBuilder sb = new StringBuilder();
        sb.append("[当前页面上下文信息]\n");
        sb.append("注意：以下信息是系统自动从当前页面收集的，AI应该直接使用这些数据回答用户问题，不要再询问用户。\n\n");
        for (Map.Entry<String, Object> entry : contextData.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null || "pageType".equals(key)) {
                continue;
            }
            if ("configSummary".equals(key) && value instanceof Map) {
                sb.append("## 配置概要\n");
                for (Map.Entry<?, ?> se : ((Map<?, ?>) value).entrySet()) {
                    if (se.getValue() != null && !"".equals(se.getValue())) {
                        sb.append("- ").append(se.getKey()).append(": ").append(se.getValue()).append("\n");
                    }
                }
                continue;
            }
            if ("formData".equals(key) && value instanceof Map) {
                sb.append("## 表单数据\n");
                for (Map.Entry<?, ?> fe : ((Map<?, ?>) value).entrySet()) {
                    if (fe.getValue() != null && !"".equals(fe.getValue())) {
                        sb.append("- ").append(fe.getKey()).append(": ").append(fe.getValue()).append("\n");
                    }
                }
                continue;
            }
            sb.append("- ").append(key).append(": ");
            if (value instanceof Map) {
                sb.append(formatMapValue((Map<?, ?>) value));
            } else if (value instanceof List) {
                List<?> listValue = (List<?>) value;
                sb.append(listValue.isEmpty() ? "(无数据)" : "[共" + listValue.size() + "项]");
            } else {
                sb.append(value);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化 Map 值为可读的 "k=v" 拼接。
     */
    private String formatMapValue(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            Object v = entry.getValue();
            if (v != null && !"".equals(v)) {
                sb.append(entry.getKey()).append("=").append(v);
                first = false;
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
