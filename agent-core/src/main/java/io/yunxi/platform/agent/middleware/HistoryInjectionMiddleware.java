package io.yunxi.platform.agent.middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

/**
 * 历史消息注入中间件。
 *
 * <p>把本次调用声明携带的会话历史拼接到输入消息之前，并按条数上限裁剪，从最新一条往前取，
 * 保证最近的上下文优先保留。历史内容与条数上限由调用方按调用维度传入，未声明时不注入。</p>
 *
 * @author yunxi-agent-platform
 */
public class HistoryInjectionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(HistoryInjectionMiddleware.class);

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        List<Msg> msgs = input.msgs();
        if (msgs == null || msgs.isEmpty()) {
            return next.apply(input);
        }
        List<Msg> history = CallContextKeys.historyMessages(ctx);
        if (history.isEmpty()) {
            return next.apply(input);
        }
        List<Msg> merged = new ArrayList<>(history);
        merged.addAll(msgs);
        log.debug("历史消息注入: history={}, current={}", history.size(), msgs.size());
        return next.apply(new AgentInput(merged));
    }
}
