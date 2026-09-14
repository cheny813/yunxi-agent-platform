package io.yunxi.platform.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.util.concurrent.TimeUnit;

/**
 * AgentScope 生命周期管理器
 * <p>通过 SmartLifecycle 实现 Agent 框架各组件的有序启停。</p>
 *
 * @author yunxi-agent-platform
 */
public class AgentscopeLifecycleManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeLifecycleManager.class);
    private volatile boolean running = false;
    private final int phase;

    /** 构造生命周期管理器，绑定其启动/停止的执行顺序相位（phase 越小越先启动、越后停止）。
     * @param phase 生命周期相位值
     */
    public AgentscopeLifecycleManager(int phase) { this.phase = phase; }

    @Override public void start() {
        if (running) return;
        log.info("AgentScope 生命周期管理器启动 (phase={})", phase);
        running = true;
    }

    @Override public void stop() {
        if (!running) return;
        log.info("AgentScope 生命周期管理器停止 (phase={})", phase);
        try {
            GracefulShutdownManager.getInstance().performGracefulShutdown();
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            log.warn("GracefulShutdownManager 类未初始化，跳过优雅关停 (phase={}): {}", phase, e.getMessage());
        } catch (Exception e) {
            log.warn("优雅关停执行异常 (phase={}): {}", phase, e.getMessage());
        }
        // Release any in-flight fire-and-forget memory background tasks (flush / maintenance
        // consolidation model calls). These tasks are dispatched asynchronously after a response is
        // returned and their underlying connections would otherwise leak until the JVM exits.
        // Cancellation is best-effort: it only takes effect on AgentScope-Java versions that expose
        // the per-owner cancellation API, older versions just wait for the tasks to settle.
        MemoryBackgroundTaskReaper.awaitQuiescenceAndCancel(5, TimeUnit.SECONDS);
        running = false;
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return phase; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
}
