package io.yunxi.platform.security.hitl;

import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.yunxi.platform.shared.config.ToolGateConfig;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 工具门控 Hook
 *
 * <p>在 Agent 推理后检查工具调用是否包含危险工具。如检测到危险工具调用，
 * 则通过 {@link PostReasoningEvent#stopAgent()} 暂停 Agent，等待人类确认。
 *
 * <p>优先级: 55（在 TextToolCallParserHook 之后、ReasoningReviewHook 之前执行）
 *
 * <p>V2.0 对应 Middleware: {@link io.yunxi.platform.agent.middleware.ToolGateMiddleware}
 */
public class ToolGateHook {

    private static final Logger log = LoggerFactory.getLogger(ToolGateHook.class);

    /** 危险工具名称集合 */
    private final Set<String> dangerousTools;

    /** Hook 优先级 */
    static final int PRIORITY = 55;

    /**
     * 创建工具门控 Hook
     *
     * @param config 工具门控配置
     */
    public ToolGateHook(ToolGateConfig config) {
        this.dangerousTools = config.getTools() != null
                ? Set.copyOf(config.getTools())
                : Set.of();
    }

    /**
     * 处理推理后事件，检查工具调用并决定是否暂停 Agent
     *
     * @param event 推理后事件
     * @return Mono
     */
    public Mono<Void> onEvent(PostReasoningEvent event) {
        return Mono.fromRunnable(() -> {
            Msg msg = event.getReasoningMessage();
            if (msg == null) {
                return;
            }
            List<?> blocks = msg.getContent();
            if (blocks == null) {
                return;
            }
            for (Object block : blocks) {
                if (block instanceof ToolUseBlock tub) {
                    if (dangerousTools.contains(tub.getName())) {
                        log.warn("检测到危险工具调用: {}，暂停 Agent 等待人工确认", tub.getName());
                        event.stopAgent();
                        return;
                    }
                }
            }
        });
    }

    /**
     * 返回 Hook 优先级
     *
     * @return 优先级值
     */
    public int priority() {
        return PRIORITY;
    }
}
