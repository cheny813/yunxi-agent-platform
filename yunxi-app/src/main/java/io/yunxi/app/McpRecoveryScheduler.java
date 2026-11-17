package io.yunxi.app;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP 构建期失败的恢复探测调度器：定时调用 {@link AgentProvisioner#probeAndRebuild()}，
 * 对布建期登记的不可达 MCP 服务器探活，任一恢复可达即触发对应 Agent 实例换装。
 * 单线程固定间隔探测（默认 30s，经 {@code yunxi.mcp.recovery-probe-interval-seconds} 调整）；
 * 待换装登记为空时探测即时返回，无额外开销。
 */
@Component
public class McpRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(McpRecoveryScheduler.class);

    private final AgentProvisioner agentProvisioner;
    private final long probeIntervalSeconds;
    private ScheduledExecutorService executor;

    public McpRecoveryScheduler(
            AgentProvisioner agentProvisioner,
            @Value("${yunxi.mcp.recovery-probe-interval-seconds:30}") long probeIntervalSeconds) {
        this.agentProvisioner = agentProvisioner;
        this.probeIntervalSeconds = probeIntervalSeconds;
    }

    @PostConstruct
    public void start() {
        if (probeIntervalSeconds <= 0) {
            log.info("MCP 恢复探测已禁用（yunxi.mcp.recovery-probe-interval-seconds={})", probeIntervalSeconds);
            return;
        }
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "mcp-recovery-probe");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        agentProvisioner.probeAndRebuild();
                    } catch (Exception e) {
                        log.warn("MCP 恢复探测轮次异常（不影响下轮调度）", e);
                    }
                },
                probeIntervalSeconds,
                probeIntervalSeconds,
                TimeUnit.SECONDS);
        log.info("MCP 恢复探测已启动，间隔 {}s", probeIntervalSeconds);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
