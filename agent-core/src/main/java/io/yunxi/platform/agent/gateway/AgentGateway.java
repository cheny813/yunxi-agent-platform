package io.yunxi.platform.agent.gateway;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.agent.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Agent 网关接口：负责将外部请求转化为 AgentScope 原生 Agent 调用，并映射事件流 / 中断信号。
 */
public interface AgentGateway {

    /**
     * 以流式事件方式调用 Agent。
     *
     * @param agentName   Agent 名称
     * @param message     用户输入消息
     * @param userId     用户 ID（多租户隔离，注入 RuntimeContext）
     * @param sessionId  会话 ID（多租户隔离，注入 RuntimeContext）
     * @return AgentScope 原生 {@link AgentEvent} 事件流
     */
    Flux<AgentEvent> callStream(String agentName, String message, String userId, String sessionId);

    /**
     * 中断指定 Agent 的当前执行（AgentScope 原生协作式中断，下次调用自动恢复）。
     *
     * @param agentName Agent 名称
     */
    void interrupt(String agentName);
}

/**
 * Agent 网关默认实现。
 *
 * <p>本网关通过 AgentScope 原生能力实现：
 * <ul>
 *   <li>调用入口改用 AgentScope 原生 {@link HarnessAgent#streamEvents(List, RuntimeContext)}
 *       （细粒度 {@code AgentEvent} 事件流，替代自研 SSE 适配）；
 *       注意 {@code streamEvents} 是 HarnessAgent/ReActAgent 的原生方法，
 *       {@code Agent} 接口未声明，故对 {@link AgentService#getAgentInstance(String)}
 *       返回值做转型后调用。</li>
 *   <li>中断改用 AgentScope 原生 {@link Agent#interrupt()}，无 userId/sessionId 二参重载。</li>
 * </ul>
 * </p>
 */
@Component
class AgentGatewayImpl implements AgentGateway {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(AgentGatewayImpl.class);

    /** Agent 服务，用于获取已注册的 Agent 实例 */
    private final AgentService agentService;

    /**
     * 构造 Agent 网关。
     *
     * @param agentService Agent 服务
     */
    public AgentGatewayImpl(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 以流式事件方式调用 Agent，将外部请求转化为 AgentScope 原生 {@link HarnessAgent#streamEvents} 调用。
     * @param agentName  Agent 名称
     * @param message    用户输入消息
     * @param userId     用户ID（注入 RuntimeContext 实现多租户隔离）
     * @param sessionId  会话ID（注入 RuntimeContext 实现多租户隔离）
     * @return AgentScope 原生 {@link AgentEvent} 事件流
     */
    @Override
    public Flux<AgentEvent> callStream(String agentName, String message, String userId, String sessionId) {
        Agent agent = agentService.getAgentInstance(agentName);
        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        // streamEvents 是 HarnessAgent/ReActAgent 的原生方法（Agent 接口未声明），需转型调用；
        // 重载签名为 streamEvents(List<Msg>, RuntimeContext)（非单 Msg）。
        // 注意：共享 Agent 实例生命周期由框架管理，绝不可 close（内联转换避免 JDT resource-leak 误报）
        return ((HarnessAgent) agent).streamEvents(
                List.of(Msg.builder().textContent(message).build()), ctx);
    }

    /**
     * 中断指定 Agent 的当前执行。
     *
     * <p>委托给 AgentScope 原生 {@link Agent#interrupt()}，为协作式一次性中断信号，
     * 下一次调用将自动恢复。</p>
     *
     * @param agentName Agent 名称
     */
    @Override
    public void interrupt(String agentName) {
        log.info("中断 Agent: {}", agentName);
        agentService.getAgentInstance(agentName).interrupt();
    }
}
