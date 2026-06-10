package io.yunxi.platform.agent.middleware;

import io.agentscope.core.agent.Agent;
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
 * 阻断模式：检测到提示注入关键词时立即中断执行，抛出 {@link ContentBlockedException}。
 * 用于防止用户输入恶意指令劫持 LLM 行为。
 * </p>
 *
 * <p>
 * 工作流程：
 * <ol>
 * <li>在 Agent 入口（onAgent）拦截所有请求</li>
 * <li>遍历输入消息，提取文本内容</li>
 * <li>对文本进行大小写不敏感的关键词匹配</li>
 * <li>匹配到注入模式时阻断执行</li>
 * <li>全部安全则放行到下游</li>
 * </ol>
 * </p>
 *
 * <p>
 * V2.0 升级：替代 ContentFilterHook，使用 MiddlewareBase.onAgent 拦截。
 * </p>
 */
public class ContentFilterMiddleware implements MiddlewareBase {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(ContentFilterMiddleware.class);

    /**
     * 提示注入检测关键词列表
     * <p>
     * 覆盖常见的英文注入模式，匹配时忽略大小写
     * </p>
     */
    private static final List<String> INJECTION_PATTERNS = List.of(
            "ignore all previous instructions", "ignore your previous instructions",
            "you are not an AI", "you must respond as",
            "role play as", "do not follow", "disregard");

    /**
     * Agent 入口拦截：检测输入消息中的提示注入模式。
     *
     * <p>
     * 对每条输入消息的文本内容进行关键词匹配，
     * 一旦检测到注入模式立即阻断并返回错误。
     * </p>
     *
     * @param agent 当前 Agent 实例
     * @param input Agent 输入，包含用户消息列表
     * @param next  下游 Middleware 链
     * @return 事件流；检测到注入时返回错误流
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        for (var msg : input.msgs()) {
            String text = msg.getTextContent();
            if (text != null) {
                // 逐个关键词匹配，忽略大小写
                for (String pattern : INJECTION_PATTERNS) {
                    if (text.toLowerCase().contains(pattern)) {
                        log.warn("检测到可能的提示注入: agent={}, pattern={}", agent.getName(), pattern);
                        return Flux.error(new ContentBlockedException("检测到提示注入模式"));
                    }
                }
            }
        }
        // 全部安全，放行
        return next.apply(input);
    }

    /**
     * 内容阻断异常，当检测到提示注入时抛出。
     *
     * <p>
     * 上层可捕获此异常返回错误提示或记录安全事件。
     * </p>
     */
    public static class ContentBlockedException extends RuntimeException {

        /**
         * 创建内容阻断异常。
         *
         * @param message 阻断原因描述
         */
        public ContentBlockedException(String message) {
            super(message);
        }
    }
}
