package io.yunxi.platform.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.yunxi.platform.conversation.ConversationDomainService;
import io.yunxi.platform.execution.operator.PhaseTracker;
import io.yunxi.platform.execution.spi.AgentEventAdapter;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.util.SseMessageBuilder;
import io.yunxi.platform.tracing.LlmMetrics;

/**
 * SSE 事件适配器。
 *
 * <p>负责把框架 {@link AgentEvent} 转换为 0..n 条 SSE 消息字符串：</p>
 * <ul>
 *   <li>已消费事件：TEXT/THINKING_BLOCK_DELTA（流式输出 + 推理累积）、块边界事件（消费丢弃）；</li>
 *   <li>AGENT_RESULT：usage 记录、thinking 元数据注入、会话持久化、分块输出（200 字符/块）；</li>
 *   <li>透传模式：TOOL_CALL_START/END、TOOL_RESULT_END 附加 UX 友好状态消息，
 *       MODEL_CALL_START/END 不附加，其余事件原样透传（buildAgentEvent）。</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 */
@Component
public class SseAgentEventAdapter implements AgentEventAdapter {

    /** AGENT_RESULT 文本分块大小 */
    private static final int RESULT_CHUNK_SIZE = 200;
    /** 推理累积器在 ExecutionContext.attributes 中的键 */
    private static final String ATTR_THINKING_ACCUMULATOR = "thinkingAccumulator";

    private final SseMessageBuilder sseMessageBuilder;
    private final LlmMetrics llmMetrics;
    private final ConversationDomainService conversationDomainService;

    public SseAgentEventAdapter(SseMessageBuilder sseMessageBuilder,
                                LlmMetrics llmMetrics,
                                ConversationDomainService conversationDomainService) {
        this.sseMessageBuilder = sseMessageBuilder;
        this.llmMetrics = llmMetrics;
        this.conversationDomainService = conversationDomainService;
    }

    @Override
    public List<String> onStart(ExecutionContext ctx) {
        return List.of(ctx.getConversationId() != null
                ? sseMessageBuilder.buildStartMessageWithConversationId(ctx.getConversationId())
                : sseMessageBuilder.buildStartMessage());
    }

    @Override
    public List<String> onThinking(String thinkingText, ExecutionContext ctx) {
        if (thinkingText == null) {
            return List.of();
        }
        // A2A/深度协作模式用状态消息承载思考提示，普通模式用 thinking 事件
        boolean useA2A = ctx.getRequest().isUseA2A() || ctx.getRequest().isDeepMode();
        return List.of(useA2A
                ? sseMessageBuilder.buildAgentStatusMessage(thinkingText)
                : sseMessageBuilder.buildThinkingMessage(thinkingText));
    }

    @Override
    public String onError(String errorMessage, ExecutionContext ctx) {
        return sseMessageBuilder.buildErrorMessage(errorMessage);
    }

    @Override
    public List<String> convert(AgentEvent event, ExecutionContext ctx) {
        AgentEventType type = event.getType();

        // ── 已消费事件：流式文本 / 推理增量 ──
        if (type == AgentEventType.TEXT_BLOCK_DELTA) {
            String delta = event instanceof TextBlockDeltaEvent
                    ? ((TextBlockDeltaEvent) event).getDelta()
                    : null;
            return delta != null && !delta.isEmpty()
                    ? List.of(sseMessageBuilder.buildContentMessage(delta))
                    : List.of();
        }
        if (type == AgentEventType.THINKING_BLOCK_DELTA) {
            String text = event instanceof ThinkingBlockDeltaEvent
                    ? ((ThinkingBlockDeltaEvent) event).getDelta()
                    : null;
            if (text != null && !text.isEmpty()) {
                thinkingAccumulator(ctx).append(text);
                return List.of(sseMessageBuilder.buildThinkingMessage(text));
            }
            return List.of();
        }

        // 文本/思考块边界事件（START/END）：块内容已由 *_BLOCK_DELTA 消费替换，直接消费丢弃
        if (type == AgentEventType.TEXT_BLOCK_START
                || type == AgentEventType.TEXT_BLOCK_END
                || type == AgentEventType.THINKING_BLOCK_START
                || type == AgentEventType.THINKING_BLOCK_END) {
            return List.of();
        }

        // 最终结果：usage 记录 + thinking 元数据注入 + 会话持久化 + 分块输出
        if (type == AgentEventType.AGENT_RESULT) {
            return handleAgentResult(event, ctx);
        }

        // ── 阶段标记：PhaseTracker 注入的 agent_status 阶段事件 → agent_status 协议负载 ──
        // 阶段标记以原生事件形式进入适配器，此处转换为 agent_status 消息，
        // 前端据此渲染状态视图（IDLE→THINKING→TOOL_CALL→ANSWER→DONE），不透传原生事件。
        // content 为 JSON 字符串 {"phase","label"}：前端 JSON.parse 得到对象后按 phase 渲染，
        // 与透传分支的纯文本 UX 文案（"正在执行: X" / "X 完成"）并存，各司其职。
        if (type == AgentEventType.CUSTOM && event instanceof CustomEvent customEvent) {
            if (PhaseTracker.AGENT_STATUS_EVENT_NAME.equals(customEvent.getName())) {
                Object phase = customEvent.getValue().get("phase");
                Object label = customEvent.getValue().get("label");
                String statusJson = "{\"phase\":\"" + (phase != null ? phase : "UNKNOWN")
                        + "\",\"label\":\"" + (label != null ? label : "处理中") + "\"}";
                return List.of(sseMessageBuilder.buildAgentStatusMessage(statusJson));
            }
        }

        // ── 透传模式：UX 友好消息 + 原生事件透传 ──
        List<String> messages = new ArrayList<>();
        switch (type) {
            case MODEL_CALL_START:
            case MODEL_CALL_END:
                // 不附加 agent_status：qwen-plus 等模型不产生 THINKING_BLOCK_DELTA，
                // MODEL_CALL_START 几乎与 TEXT_BLOCK_DELTA 同时到达，推理块状态无意义
                break;
            case TOOL_CALL_START: {
                ToolCallStartEvent tc = (ToolCallStartEvent) event;
                messages.add(sseMessageBuilder.buildToolCallMessage(tc.getToolCallId(), tc.getToolCallName()));
                messages.add(sseMessageBuilder.buildAgentStatusMessage(
                        "正在执行: " + friendlyToolName(tc.getToolCallName())));
                break;
            }
            case TOOL_CALL_END: {
                ToolCallEndEvent tc = (ToolCallEndEvent) event;
                messages.add(sseMessageBuilder.buildToolCallDoneMessage(tc.getToolCallId(), tc.getToolCallName()));
                break;
            }
            case TOOL_RESULT_END: {
                ToolResultEndEvent tr = (ToolResultEndEvent) event;
                String stateValue = tr.getState() != null ? tr.getState().getValue() : "unknown";
                String stateLabel = "SUCCESS".equalsIgnoreCase(stateValue) ? " 完成" : " (" + stateValue + ")";
                messages.add(sseMessageBuilder.buildToolResultMessage(
                        tr.getToolCallId(), tr.getToolCallName(), stateValue));
                messages.add(sseMessageBuilder.buildAgentStatusMessage(
                        friendlyToolName(tr.getToolCallName()) + stateLabel));
                break;
            }
            default:
                break;
        }
        // 原生事件透传（所有事件，包括已发 agent_status 的）
        messages.add(sseMessageBuilder.buildAgentEvent(event));
        return messages;
    }

    private List<String> handleAgentResult(AgentEvent event, ExecutionContext ctx) {
        if (!(event instanceof AgentResultEvent resultEvent)) {
            return List.of();
        }
        Msg resultMsg = resultEvent.getResult();
        ChatUsage usage = resultMsg != null ? resultMsg.getUsage() : null;
        if (usage != null) {
            llmMetrics.recordAndLogUsage(
                    ctx.getConversationId() != null ? ctx.getConversationId() : "stream", "yunxi", usage);
        }

        // 推理文本累积器内容注入 Msg metadata
        String reasoningText = thinkingAccumulator(ctx).toString();
        if (resultMsg != null && !reasoningText.isEmpty()) {
            Map<String, Object> metadata = new HashMap<>();
            if (resultMsg.getMetadata() != null) {
                metadata.putAll(resultMsg.getMetadata());
            }
            metadata.put("thinking", reasoningText);
            resultMsg = Msg.builder()
                    .role(resultMsg.getRole())
                    .textContent(resultMsg.getTextContent())
                    .metadata(metadata)
                    .build();
        }

        String text = resultMsg != null ? resultMsg.getTextContent() : null;
        if (text != null && !text.isEmpty()) {
            // 持久化会话：用户消息已在门面层 addMessage，此处补存助手回复，
            // 确保刷新 UI 后能恢复完整对话
            ConversationEntity conversation = ctx.getConversation();
            if (conversation != null) {
                conversation.addMessage(resultMsg);
                conversationDomainService.saveConversation(conversation);
            }
            return splitTextIntoChunks(text, RESULT_CHUNK_SIZE).stream()
                    .map(sseMessageBuilder::buildContentMessage)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private StringBuilder thinkingAccumulator(ExecutionContext ctx) {
        Object existing = ctx.getAttribute(ATTR_THINKING_ACCUMULATOR);
        if (existing instanceof StringBuilder sb) {
            return sb;
        }
        StringBuilder sb = new StringBuilder();
        ctx.setAttribute(ATTR_THINKING_ACCUMULATOR, sb);
        return sb;
    }

    /**
     * 将文本按固定大小切分为块。
     */
    private static List<String> splitTextIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return chunks;
    }

    /**
     * 将 AgentScope 工具名称映射为中文描述（用于 A2A 推理块状态展示）。
     */
    private static String friendlyToolName(String toolName) {
        switch (toolName) {
            case "agent_spawn":         return "启动专家智能体";
            case "agent_send":          return "与专家智能体沟通";
            case "agent_list":          return "列出可用智能体";
            case "task_list":           return "查看任务列表";
            case "task_output":         return "产出任务结果";
            case "task_cancel":         return "取消任务";
            case "read_file":           return "读取文件";
            case "write_file":          return "写入文件";
            case "edit_file":           return "编辑文件";
            case "list_files":          return "浏览目录";
            case "grep_files":          return "搜索文件内容";
            case "glob_files":          return "匹配文件";
            case "execute":             return "执行命令";
            case "memory_search":       return "搜索记忆";
            case "memory_get":          return "获取记忆";
            case "session_history":     return "获取会话历史";
            case "session_list":        return "列出会话";
            case "session_search":      return "搜索会话";
            case "load_skill_through_path": return "加载技能";
            default:                    return toolName;
        }
    }
}
