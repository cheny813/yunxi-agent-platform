package io.yunxi.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 锁住 {@link SessionTurnGuard} 这一唯一的取/放闸门实现：所有协议入口（web / Chat Completions 经
 * DefaultExecutionFacade，AG-UI 经 YunxiGuardedAguiAgentAdapter）都必须经此一处。本测试保证其语义不被改写，
 * 是「四协议并发正确性不漂移」的底层护栏——此前该逻辑曾在多个协议各自实现而分叉。
 */
class SessionTurnGuardTest {

    private static SessionTurnGate gateReturning(TurnLease lease) {
        return new SessionTurnGate() {
            @Override
            public TurnLease acquire(String key) {
                return lease;
            }

            @Override
            public boolean isRunning(String key) {
                return false;
            }
        };
    }

    @Test
    void nullGatePassesThroughWithoutAcquiring() {
        // 闸门为 null（单机内存后端）时直接透传，不得取锁、不得释放。
        List<String> result = SessionTurnGuard.guard(Flux.just("a"), "k", null).collectList().block();
        assertEquals(List.of("a"), result);
    }

    @Test
    void acquiresOnceAndReleasesAfterCompletion() {
        boolean[] closed = {false};
        int[] acquired = {0};
        TurnLease lease = () -> closed[0] = true;
        SessionTurnGate gate = new SessionTurnGate() {
            @Override
            public TurnLease acquire(String key) {
                acquired[0]++;
                return lease;
            }

            @Override
            public boolean isRunning(String key) {
                return false;
            }
        };
        List<String> result = SessionTurnGuard.guard(Flux.just("a", "b"), "k", gate).collectList().block();
        assertEquals(List.of("a", "b"), result);
        assertEquals(1, acquired[0]);
        assertTrue(closed[0]);
    }

    @Test
    void busyMapsToAgentSessionBusyExceptionNotInterrupted() {
        SessionTurnGate gate = new SessionTurnGate() {
            @Override
            public TurnLease acquire(String key) throws TurnBusyException {
                throw new TurnBusyException(key);
            }

            @Override
            public boolean isRunning(String key) {
                return false;
            }
        };
        AgentSessionBusyException ex = assertThrows(AgentSessionBusyException.class,
                () -> SessionTurnGuard.guard(Flux.just("a"), "k", gate).blockFirst());
        assertFalse(ex.isInterrupted());
    }

    @Test
    void interruptedMapsToAgentSessionBusyExceptionInterrupted() {
        SessionTurnGate gate = new SessionTurnGate() {
            @Override
            public TurnLease acquire(String key) throws InterruptedException {
                throw new InterruptedException();
            }

            @Override
            public boolean isRunning(String key) {
                return false;
            }
        };
        AgentSessionBusyException ex = assertThrows(AgentSessionBusyException.class,
                () -> SessionTurnGuard.guard(Flux.just("a"), "k", gate).blockFirst());
        assertTrue(ex.isInterrupted());
    }
}
