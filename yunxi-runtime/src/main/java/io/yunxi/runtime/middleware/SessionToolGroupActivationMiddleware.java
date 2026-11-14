package io.yunxi.runtime.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 会话级工具组激活中间件（order=50）。
 *
 * <p>职责（单一）：在每次调用入口，把 {@link SessionToolGroupRegistry} 登记的「yunxi 托管工具组」以
 * {@code ToolContextState.addActivatedGroups}（并集，绝不删除）并入当前会话的 {@code AgentState.toolContext}。
 *
 * <p>这是 AgentScope-Java 工具组原生机制（ToolGroupScope.META + 会话级 {@code AgentState.toolContext} 持久化）的
 * 合法适配层，不是 yunxi 自造的工具组系统——「工具太多」由框架工具组解决，本中间件只负责会话级回写。它只改调用级
 * {@code AgentState}（经 {@code RuntimeContext.resolveAgentState} 取得），绝不触碰共享 toolkit 的可变激活标志，
 * 因此天然并发安全，不会因跨会话共享可变状态而相互覆盖。
 *
 * <p>并集语义保证：已存在的激活组（含框架 / 智能体经 meta tool 管理的）不会被移除，只补齐 build 期 canonical 组；
 * 对「持久化会话的 toolContext 为空或缺失新上线组」这类会触发 Unauthorized 的情形，一次调用即可收敛。
 */
public class SessionToolGroupActivationMiddleware implements MiddlewareBase {

    private static final int ORDER = 50;

    private final SessionToolGroupRegistry registry;

    public SessionToolGroupActivationMiddleware(SessionToolGroupRegistry registry) {
        this.registry = registry;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        List<String> groups = registry.planFor(agent.getName());
        if (!groups.isEmpty()) {
            AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
            if (state != null) {
                ToolContextState toolContext = state.getToolContext();
                if (toolContext != null) {
                    toolContext.addActivatedGroups(groups);
                }
            }
        }
        return next.apply(input);
    }
}
