package io.yunxi.platform.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 内容过滤 Middleware，在 Agent 调用入口检测提示注入模式。
 *
 * <p>
 * V2.0-RC3 适配：MiddlewareBase 方法签名新增 {@code RuntimeContext ctx} 参数。
 */
public class ContentFilterMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ContentFilterMiddleware.class);

    private static final List<String> INJECTION_PATTERNS = List.of(
            "ignore all previous instructions", "ignore your previous instructions",
            "you are not an AI", "you must respond as",
            "role play as", "do not follow", "disregard");

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        for (var msg : input.msgs()) {
            String text = msg.getTextContent();
            if (text != null) {
                for (String pattern : INJECTION_PATTERNS) {
                    if (text.toLowerCase().contains(pattern)) {
                        log.warn("检测到可能的提示注入: agent={}, pattern={}", agent.getName(), pattern);
                        return Flux.error(new ContentBlockedException("检测到提示注入模式"));
                    }
                }
            }
        }
        return next.apply(input);
    }

    public static class ContentBlockedException extends RuntimeException {
        public ContentBlockedException(String message) {
            super(message);
        }
    }
}
