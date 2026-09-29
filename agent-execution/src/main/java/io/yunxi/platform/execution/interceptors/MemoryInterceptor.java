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
 * 拦截器 150：用户消息构建。
 *
 * <p>职责：把请求参数组装成模型可读的用户消息，包含两部分与业务语义强相关的注入：
 * <ul>
 *   <li>若请求携带 {@code contextData}，将页面上下文格式化后前置到用户消息（见
 *       {@link #formatContextData}）；</li>
 *   <li>若请求携带人机确认回传结果，转换为框架的 {@code ConfirmResult} 并写入消息元数据。</li>
 * </ul>
 * 历史消息的裁剪与拼接不在此处完成，已由请求级中间件在调用入口统一处理。</p>
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

        // 1. 构建基础用户消息（含 contextData 页面上下文注入）
        Msg userMsg = buildUserMessage(message, contextData);
        // 2. 注入人机确认（HITL）回传结果：确认结果是输入消息的有机组成部分，
        //    与用户消息构建一并处理，避免单独维护一条仅做 DTO 转换的拦截器。
        userMsg = enrichWithConfirmResults(userMsg, req.getConfirmResults(), ctx);
        // 2.1 HITL 恢复轮的载荷约束：确认轮不是一次新的用户发问，而是对上一轮挂起工具调用的
        //     答复。框架在 validateAndAcceptConfirmResults 通过后直接 return resumeAgent()
        //     跳进执行阶段（ReActAgent.java:1769-1771），此时调用方若额外提交页面上的一批新
        //     问题，会因为「直接 resume、不补推理」而永远不被回答。静默丢弃比让用户以为已被
        //     受理更安全；要追问请等本轮恢复的流结束之后另起一次请求。
        if (hasConfirmResults(req)) {
            log.info("[HITL] 确认轮不携带用户新问题，仅回传确认结果: agentName={}, conversationId={}",
                    ctx.getAgentName(), ctx.getConversationId());
            userMsg = stripUserText(userMsg);
        }

        // 3. 历史消息的裁剪与拼接由请求级中间件承担，此处只提供单条用户消息
        List<Msg> allMessages = new ArrayList<>();
        allMessages.add(userMsg);

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

    /** 请求是否携带有效的人机确认回传结果（至少一项带 toolCallId）。 */
    private boolean hasConfirmResults(ExecutionRequest req) {
        List<ConfirmResultRequest> requests = req.getConfirmResults();
        if (requests == null || requests.isEmpty()) {
            return false;
        }
        for (ConfirmResultRequest r : requests) {
            if (r != null && r.getToolCallId() != null && !r.getToolCallId().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉消息的文本内容，仅保留元数据（确认结果）。
     *
     * <p>框架在确认轮走的是「直接 resume」路径，用户文本不会触发新的推理；把它留在上下文里只
     * 会污染后续轮次，故此处清空。</p>
     */
    private Msg stripUserText(Msg userMsg) {
        if (userMsg.getContent() == null || userMsg.getContent().isEmpty()) {
            return userMsg;
        }
        return Msg.builder()
                .id(userMsg.getId())
                .name(userMsg.getName())
                .role(userMsg.getRole())
                .metadata(userMsg.getMetadata())
                .build();
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
