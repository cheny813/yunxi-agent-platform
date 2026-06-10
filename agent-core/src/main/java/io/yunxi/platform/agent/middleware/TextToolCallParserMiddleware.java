package io.yunxi.platform.agent.middleware;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;

/**
 * 文本工具调用解析 Middleware
 *
 * <p>
 * V2.0-RC1 适配：ToolCallEndEvent.getToolCalls()/getText() 在当前版本中被移除。
 * 此 Middleware 暂时作为透传通道，完整功能等待 API 稳定后启用。
 * </p>
 */
public class TextToolCallParserMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(TextToolCallParserMiddleware.class);

    private final Toolkit toolkit;
    private final boolean strictMode;

    public TextToolCallParserMiddleware(Toolkit toolkit, boolean strictMode) {
        this.toolkit = toolkit;
        this.strictMode = strictMode;
    }

    public TextToolCallParserMiddleware(Toolkit toolkit) {
        this(toolkit, true);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        // V2.0-RC1: ToolCallEndEvent API 已变更，暂做透传
        // TODO: 等待 V2.0 稳定后重新实现文本工具调用解析
        return next.apply(input);
    }
}
