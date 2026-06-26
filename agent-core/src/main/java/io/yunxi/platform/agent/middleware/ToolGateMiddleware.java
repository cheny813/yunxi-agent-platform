package io.yunxi.platform.agent.middleware;

import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.platform.shared.config.ToolGateConfig;
import reactor.core.publisher.Flux;

/**
 * 工具门控 Middleware。
 *
 * <p>
 * V2.0-RC3: {@link ActingInput} 是 Java record，通过 {@code input.toolCalls()}
 * 获取工具调用列表。
 * 在 {@code onActing} 中检查工具调用是否在危险列表中，若命中则阻断并抛出异常。
 * 同时利用 RC3 工具事件 {@code toolCallName} 字段做事件级别的补充拦截。
 * </p>
 *
 * <p>
 * 决策优先级：工具名匹配 → 事件级别拦截 → pass-through。
 * 目前 ActingInput 中已有完整的 toolCalls 列表，无需 event 级别兜底。
 * </p>
 */
public class ToolGateMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolGateMiddleware.class);

    private final Set<String> dangerousTools;
    private final String messageTemplate;

    public ToolGateMiddleware(ToolGateConfig config) {
        this.dangerousTools = Set.copyOf(config.getTools());
        this.messageTemplate = config.getMessage() != null ? config.getMessage()
                : "工具 [{tool}] 需要人工确认后才能执行";
        log.info("ToolGateMiddleware 初始化完成，监控工具: {}", dangerousTools);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        // V2.0-RC3: ActingInput 是 record，input.toolCalls() 可用
        if (input.toolCalls() != null) {
            for (var tc : input.toolCalls()) {
                if (tc != null && dangerousTools.contains(tc.getName())) {
                    log.warn("工具门控阻断: tool={}, agent={}", tc.getName(), agent.getName());
                    return Flux.error(new ToolGateBlockedException(tc.getName(), messageTemplate));
                }
            }
        }
        return next.apply(input);
    }

    /**
     * 工具门控阻断异常
     */
    public static class ToolGateBlockedException extends RuntimeException {

        private final String toolName;

        public ToolGateBlockedException(String toolName, String template) {
            super(template.replace("{tool}", toolName));
            this.toolName = toolName;
        }

        public String getToolName() {
            return toolName;
        }
    }
}
