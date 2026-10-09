package io.yunxi.platform.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.platform.agent.AgentConfigurer;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 会话级工具组激活 + 权限模板回写（协议无关）。
 *
 * <p>原实现把 {@code AgentConfigurer.activateSessionToolGroups} 散落在 web/A2A 的三个执行策略
 * （StreamingStrategy / BlockingStrategy / StructuredBlockingStrategy），导致 AG-UI / Chat Completions /
     * aistio 等协议因不经这些策略而无法激活会话级工具组，持久化会话仍可能踩「MCP 工具 Unauthorized」坑。
 *
 * <p>本中间件挂在共享 Agent 的 Middleware 链上（{@code AgentConfigurer.configureMiddlewares}），
 * 经框架原生 onAgent 钩子在每次调用前统一触发，所有协议自动获得，从结构上消灭「协议 X 漏调」整类 bug。
 */
public class SessionToolGroupActivationMiddleware implements MiddlewareBase {

    @Override
    public int order() {
        // 早于推理与工具调用，确保工具组在首次工具调用前已就绪
        return 5;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (agent instanceof HarnessAgent harnessAgent) {
            AgentConfigurer.activateSessionToolGroups(
                    harnessAgent, ctx.getUserId(), ctx.getSessionId());
        }
        return next.apply(input);
    }
}
