package io.yunxi.platform.infra.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.agentscope.core.shutdown.GracefulShutdownManager;

/**
 * AgentScope 生命周期管理器
 * <p>
 * 通过 SmartLifecycle 实现 Agent 框架各组件的有序启停。
 * 现在启动顺序是：Model(0) → Toolkit(1) → Memory(2) → Session(3) → Agent(4)，
 * 关闭顺序反过来。AgentConfigurer 的 phase=5，在基础设施就绪后才开始装配 Agent。
 * </p>
 * <p>
 * 以前用 @EventListener(ApplicationReadyEvent)，所有组件同时启动，没有先后，
 * 靠运气。现在明确控制顺序。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class AgentscopeLifecycleManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeLifecycleManager.class);

    private volatile boolean running = false;

    private final int phase;

    public AgentscopeLifecycleManager(int phase) {
        this.phase = phase;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        log.info("AgentScope 生命周期管理器启动 (phase={})", phase);
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        log.info("AgentScope 生命周期管理器停止 (phase={})", phase);
        GracefulShutdownManager.getInstance().performGracefulShutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return phase;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }
}
