package io.yunxi.runtime;

import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.context.ApplicationEvent;

/**
 * 运行时平面在 Agent 装配完成并注册后发布的事件。
 *
 * <p>这是与具体集成协议无关的通用生命周期事件：yunxi-fabric 等适配层可据此接线（例如把 Agent 委托给
 * aistio 控制面）。运行时平面不感知任何 aistio 概念，仅发布本事件，保持「运行时单一装配点、fabric 单一集成点」的边界。</p>
 */
public class AgentBuiltEvent extends ApplicationEvent {

    private final HarnessAgent agent;

    public AgentBuiltEvent(Object source, HarnessAgent agent) {
        super(source);
        this.agent = agent;
    }

    /** 返回事件携带的已装配 Agent（供集成层接线时使用）。 */
    public HarnessAgent getAgent() {
        return agent;
    }
}
