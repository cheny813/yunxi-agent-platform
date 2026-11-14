package io.yunxi.app;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.executor.runner.BaseReActAgentRunner;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * A2A 执行器：把 yunxi 构建的 HarnessAgent 适配为框架 A2A server 所需的 AgentRunner。
 *
 * <p>A2A 协议层服务端由框架 AgentScopeA2aServer 提供，本类仅把底层 ReActAgent delegate 透传给框架，
 * 不重建任何推理语义（协议语义全部落在框架 AgentScopeA2aServer / TransportWrapper）。
 */
public class HarnessAgentRunner extends BaseReActAgentRunner {

    private final HarnessAgent harnessAgent;

    /** 包裹一个 yunxi 构建的 HarnessAgent，供框架 A2A server 取用其底层 ReActAgent。 */
    public HarnessAgentRunner(HarnessAgent harnessAgent) {
        this.harnessAgent = harnessAgent;
    }

    /** 返回被包裹 HarnessAgent 的底层 ReActAgent delegate（推理由框架 A2A 协议层驱动）。 */
    @Override
    protected ReActAgent buildReActAgent() {
        return harnessAgent.getDelegate();
    }
}
