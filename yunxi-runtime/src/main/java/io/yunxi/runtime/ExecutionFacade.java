package io.yunxi.runtime;

import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 运行时平面。统一执行门面。运行一个 Agent 的唯一入口：经 {@link #execute(AgentCommand)} 流式产出事件，
 * 经 {@link #settle(AgentCommand)} 结算终态结果。四种协议都汇聚到这一个接口。
 *
 * <p>Agent 查找经 {@link AgentLookup}（应用平面薄委托 seam，底层为框架协议层 {@code AguiAgentRegistry}
 * 单例 Agent 真相源）完成；yunxi 不维护第二份 Agent 注册表。</p>
 */
public interface ExecutionFacade {

    /** 执行一个 Agent 命令，流式产出推理事件（含文本增量与终态结果）。 */
    Flux<AgentEvent> execute(AgentCommand command);

    /** 结算命令的终态结果（OK / 各类异常），供同步协议取最终答复。 */
    Mono<Settle> settle(AgentCommand command);
}
