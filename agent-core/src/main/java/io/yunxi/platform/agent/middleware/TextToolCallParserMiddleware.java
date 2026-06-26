package io.yunxi.platform.agent.middleware;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;

/**
 * 文本工具调用解析 Middleware。
 *
 * <p>
 * V2.0-RC3: 当前做为透传通道使用。
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
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }
}
