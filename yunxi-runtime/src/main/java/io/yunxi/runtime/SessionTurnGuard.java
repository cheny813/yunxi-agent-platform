package io.yunxi.runtime;

import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import reactor.core.publisher.Flux;

/**
 * 会话轮次闸门的单一包装点：所有协议入口（web / Chat Completions 经 {@link DefaultExecutionFacade}，
 * AG-UI 经 {@code YunxiGuardedAguiAgentAdapter}）都必须经此一处取/放闸门，杜绝各协议各自实现闸门逻辑
 * 导致的并发语义漂移。
 *
 * <p>取锁在 {@link Flux#defer} 内惰性发生（仅当订阅时），释放注册在 {@code doFinally}，无论成功、失败还是
 * 取消都保证释放，避免死锁。闸门为 {@code null} 时直接透传源流（单机内存后端场景）。</p>
 */
public final class SessionTurnGuard {

    private SessionTurnGuard() {
    }

    /**
     * 用分布式会话轮次闸门包住源流。
     *
     * @param source 真正驱动 Agent 的事件流
     * @param key    轮次键，约定为 {@code userId:sessionId}
     * @param gate   闸门；{@code null} 时直接透传
     * @param <T>    事件类型
     * @return 已套用闸门取/放的事件流
     */
    public static <T> Flux<T> guard(Flux<T> source, String key, SessionTurnGate gate) {
        if (gate == null) {
            return source;
        }
        return Flux.defer(() -> {
            TurnLease lease;
            try {
                lease = gate.acquire(key);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Flux.error(new AgentSessionBusyException(key, true));
            } catch (TurnBusyException e) {
                return Flux.error(new AgentSessionBusyException(key, false));
            }
            return source.doFinally(signal -> lease.close());
        });
    }
}
