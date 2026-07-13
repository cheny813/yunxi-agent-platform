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
 * 本 Middleware 实现 {@link MiddlewareBase} 的 {@code onAgent} 拦截点，方法签名接收
 * {@code RuntimeContext ctx}（运行时上下文，承载 userId / sessionId 等调用信息）。
 */
public class ContentFilterMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ContentFilterMiddleware.class);

    /** 提示注入检测模式列表（小写子串匹配），命中任一即判定为注入尝试 */
    private static final List<String> INJECTION_PATTERNS = List.of(
            "ignore all previous instructions", "ignore your previous instructions",
            "you are not an AI", "you must respond as",
            "role play as", "do not follow", "disregard");

    /** Agent 调用入口拦截：遍历输入消息文本，命中提示注入模式时直接以错误流短路，否则放行至下一节点。
     * @param agent 目标 Agent
     * @param ctx   运行时上下文（承载 userId / sessionId）
     * @param input 当前 Agent 输入（含消息列表）
     * @param next  链路下一节点处理器
     * @return 正常时透传下一节点事件流；命中注入时返回错误事件流
     */
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

    /**
     * 内容被拦截时抛出的运行时异常（提示注入命中）。
     */
    public static class ContentBlockedException extends RuntimeException {
        /**
         * 构造内容被拦截异常。
         *
         * @param message 异常描述信息，说明被拦截的原因（如命中的注入关键词）
         */
        public ContentBlockedException(String message) {
            super(message);
        }
    }
}
