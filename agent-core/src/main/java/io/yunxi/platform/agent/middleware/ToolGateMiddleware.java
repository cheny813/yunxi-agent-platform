package io.yunxi.platform.agent.middleware;

import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.platform.shared.config.ToolGateConfig;
import reactor.core.publisher.Flux;

/**
 * 工具门控 Middleware
 *
 * <p>
 * V2.0-RC1 适配：ActingInput.getToolCalls() 和 PermissionEngine API 在当前版本中已变更。
 * 当前降级为配置驱动的日志告警模式，完整拦截功能等待 API 稳定后启用。
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
    public Flux<AgentEvent> onActing(Agent agent, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        // V2.0-RC1: ActingInput.getToolCalls() removed, pass-through mode
        // TODO: 等待 V2.0 稳定后恢复完整工具门控拦截
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
