package io.yunxi.app.distributed;

import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.yunxi.runtime.DistributedRuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 跨实例会话互斥启动护栏（确保多副本部署下跨实例会话互斥后端被显式装配的落地实现）。
 *
 * <p>主平台默认 {@link DistributedRuntimeConfig} 为内存态，其 {@code sessionTurnGate()} 返回
 * AgentScope-Java 框架进程内公平信号量 {@link LocalSessionTurnGate}——仅保证单 JVM 内同会话串行化，
 * 不跨副本。多副本部署下若仍为此闸门，跨实例会话写会丢失互斥，属静默正确性缺陷。
 *
 * <p>本护栏在应用上下文就绪后观测单一真相源 {@code DistributedRuntimeConfig.sessionTurnGate()}，据此：
 * <ul>
 *   <li>默认（enforced=false）：仅记录 WARN，提示部署可能缺少跨实例互斥后端；单实例是合法部署，不阻断。</li>
 *   <li>enforced=true：直接 fail-closed，启动抛异常，避免多副本部署在缺跨实例互斥时静默运行。</li>
 * </ul>
 *
 * <p>不引入第二份真相源或新锁；修复手段是让部署显式装配跨实例 {@code DistributedRuntimeConfig} 后端
 * （如复用平台 Redis 的跨实例实现），而非本护栏兜底执行。
 */
@Component
public class CrossInstanceTurnGateGuardian implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CrossInstanceTurnGateGuardian.class);

    private final DistributedRuntimeConfig distributedRuntimeConfig;
    private final boolean crossInstanceEnforced;

    public CrossInstanceTurnGateGuardian(
            DistributedRuntimeConfig distributedRuntimeConfig,
            @Value("${yunxi.distributed.cross-instance.enforced:false}") boolean crossInstanceEnforced) {
        this.distributedRuntimeConfig = distributedRuntimeConfig;
        this.crossInstanceEnforced = crossInstanceEnforced;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!(distributedRuntimeConfig.sessionTurnGate() instanceof LocalSessionTurnGate)) {
            // 已装配跨实例闸门（如复用平台 Redis 后端的实现），无静默缺陷。
            return;
        }
        String guidance = "当前 DistributedRuntimeConfig 为内存态，sessionTurnGate 为进程内 LocalSessionTurnGate，"
                + "不跨副本；多副本部署将丢失跨实例会话互斥。请装配跨实例 DistributedRuntimeConfig 后端"
                + "（复用平台 Redis 共享存储）以启用跨实例互斥。";
        if (crossInstanceEnforced) {
            throw new IllegalStateException(
                    "跨实例会话互斥未建立却要求强制（yunxi.distributed.cross-instance.enforced=true）：" + guidance);
        }
        log.warn("跨实例会话互斥未建立（advisory）：{} 若需多副本部署，设置"
                + " yunxi.distributed.cross-instance.enforced=true 可在缺跨实例互斥时 fail-closed。", guidance);
    }
}
