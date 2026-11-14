package io.yunxi.app;

import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

/**
 * 入口平面。协议翻译层（传输层，不携带推理语义）：把运行时门面产出的
 * AgentEvent 流（已含 userId 的 RuntimeContext 与 SessionTurnGate）翻译为 OpenAI 兼容的
 * ChatCompletionsChunk 流。复用框架 {@link ChatCompletionsChunk} 静态工厂保证输出格式与框架一致。
 *
 * <p>映射策略：文本增量事件逐一映射为 {@code textChunk}，天然去重（无需框架 adapter 的末事件去重逻辑）；
 * 工具调用在终态 {@link AgentResultEvent} 中一次性从结果消息提取（与 Agent 预装配 toolkit 的模型一致）；
 * 思考内容默认不向 OpenAI 客户端暴露，保障隐私与格式兼容。</p>
 */
public class ChatCompletionsEventMapper {

    private final String requestId;
    private final String model;
    private final ChatCompletionsResponseBuilder responseBuilder;

    /** 构造映射器：绑定本次请求 id、目标模型标识与框架响应构建器。 */
    public ChatCompletionsEventMapper(
            String requestId, String model, ChatCompletionsResponseBuilder responseBuilder) {
        this.requestId = requestId;
        this.model = model;
        this.responseBuilder = responseBuilder;
    }

    /** 把 AgentEvent 流展平映射为 ChatCompletionsChunk 流（每个事件经 {@link #map} 翻译）。 */
    public Flux<ChatCompletionsChunk> toChunks(Flux<AgentEvent> events) {
        return events.flatMap(this::map);
    }

    /** 单事件翻译：文本增量→textChunk；思考内容忽略；终态结果→内容或工具调用 + 结束块；其余事件忽略。 */
    private Flux<ChatCompletionsChunk> map(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            String text = delta.getDelta();
            if (text == null || text.isEmpty()) {
                return Flux.empty();
            }
            return Flux.just(ChatCompletionsChunk.textChunk(requestId, model, text));
        }
        if (event instanceof ThinkingBlockDeltaEvent) {
            // 思考内容默认不向 OpenAI 客户端暴露（隐私 + 格式兼容），忽略。
            return Flux.empty();
        }
        if (event instanceof AgentResultEvent result) {
            return mapResult(result.getResult());
        }
        return Flux.empty();
    }

    /** 终态消息翻译：内容非空映射文本结束块；含工具调用映射 toolCallChunk；末块携带 finish_reason。 */
    private Flux<ChatCompletionsChunk> mapResult(Msg result) {
        if (result == null) {
            return Flux.just(ChatCompletionsChunk.finishChunk(requestId, model, "stop"));
        }
        boolean hasToolCalls = result.getContent() != null
                && result.getContent().stream().anyMatch(b -> b instanceof ToolUseBlock);
        Flux<ChatCompletionsChunk> toolChunks = Flux.empty();
        if (hasToolCalls) {
            var chatMessage = responseBuilder.convertMsgToChatMessage(result);
            if (chatMessage.getToolCalls() != null && !chatMessage.getToolCalls().isEmpty()) {
                toolChunks = Flux.just(
                        ChatCompletionsChunk.toolCallChunk(requestId, model, chatMessage.getToolCalls()));
            }
        }
        return toolChunks.concatWith(
                Flux.just(ChatCompletionsChunk.finishChunk(requestId, model, computeFinishReason(result))));
    }

    /** 由生成原因推导 finish_reason：工具挂起/含工具调用→tool_calls，超迭代→length，否则 stop。 */
    private String computeFinishReason(Msg result) {
        GenerateReason reason = result.getGenerateReason();
        if (reason == GenerateReason.TOOL_SUSPENDED) {
            return "tool_calls";
        }
        if (reason == GenerateReason.MAX_ITERATIONS) {
            return "length";
        }
        boolean hasToolCalls = result.getContent() != null
                && result.getContent().stream().anyMatch(b -> b instanceof ToolUseBlock);
        return hasToolCalls ? "tool_calls" : "stop";
    }
}
