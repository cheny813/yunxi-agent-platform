package io.yunxi.fabric.aistio;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.extensions.controlplane.SessionBridge;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.AgentBuiltEvent;
import io.yunxi.runtime.AgentBuildContributor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * aistio 接线装配：监听运行时 {@link AgentBuiltEvent}，把每个 {@link HarnessAgent} 委托给 aistio 控制面；
 * 应用关闭时优雅下线并关闭全部会话桥。
 *
 * <p>本类同时是构建期中间件贡献点（{@link AgentBuildContributor}）：在 Agent 构建前为每个 Agent 新建一份
 * {@link YunxiFrameworkAdapter}（框架 adapter 按 Agent 有状态），把其事件流观测中间件挂上构建器，
 * 并在构建完成后用同一份 adapter 实例完成 {@code instrument}——这样对话事件流才能在 build 期被观测到。
 * 运行时平面不感知 aistio，仅通过 AgentBuildContributor 接口与 AgentBuiltEvent 解耦。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.aistio.enabled", havingValue = "true")
public class AistioIntegration implements AgentBuildContributor {

    private static final Logger log = Logger.getLogger(AistioIntegration.class.getName());

    private final AistioConnector connector;
    private final AistioLifecycle lifecycle;
    private final Map<String, SessionBridge> bridges = new ConcurrentHashMap<>();
    private final Map<String, YunxiFrameworkAdapter> adapters = new ConcurrentHashMap<>();

    /** 注入 aistio 连接器和共享生命周期闸门。 */
    public AistioIntegration(AistioConnector connector, AistioLifecycle lifecycle) {
        this.connector = connector;
        this.lifecycle = lifecycle;
    }

    /** 构建期贡献点：为每个 Agent 新建一份有状态 {@link YunxiFrameworkAdapter} 并挂其 observer 中间件。 */
    @Override
    public MiddlewareBase buildTimeMiddleware(AgentSpec spec) {
        YunxiFrameworkAdapter adapter = new YunxiFrameworkAdapter(lifecycle);
        adapters.put(spec.identity().name(), adapter);
        return adapter.observerMiddleware();
    }

    /** 监听 Agent 构建完成事件：用构建期共建的 adapter 完成 control plane instrument，登记会话桥。 */
    @EventListener(AgentBuiltEvent.class)
    public void onAgentBuilt(AgentBuiltEvent event) {
        HarnessAgent agent = event.getAgent();
        String name = agent.getName();
        YunxiFrameworkAdapter adapter = adapters.remove(name);
        if (adapter == null) {
            return;
        }
        try {
            SessionBridge bridge = connector.instrument(name, agent, adapter);
            bridges.put(name, bridge);
            log.info("aistio 已委托 Agent: " + name);
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "aistio 委托 Agent " + name + " 失败", e);
        }
    }

    /** 应用关闭：先停止接受新任务，再逐个关闭会话桥并清空登记。 */
    @EventListener(ContextClosedEvent.class)
    public void onShutdown(ContextClosedEvent event) {
        lifecycle.stopAccepting();
        for (Map.Entry<String, SessionBridge> entry : bridges.entrySet()) {
            try {
                entry.getValue().close();
                log.info("aistio 会话桥已关闭: " + entry.getKey());
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "aistio 关闭会话桥 " + entry.getKey() + " 失败", e);
            }
        }
        bridges.clear();
        adapters.clear();
    }
}
