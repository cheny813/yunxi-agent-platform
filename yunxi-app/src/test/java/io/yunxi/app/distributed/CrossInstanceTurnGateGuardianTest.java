package io.yunxi.app.distributed;

import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.TurnLease;
import io.yunxi.runtime.DistributedRuntimeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrossInstanceTurnGateGuardianTest {

    /** 跨实例闸门桩：非 LocalSessionTurnGate，acquire/isRunning 在测试中不被调用。 */
    private static final SessionTurnGate CROSS_INSTANCE_GATE = new SessionTurnGate() {
        @Override
        public TurnLease acquire(String key) throws InterruptedException, TurnBusyException {
            throw new UnsupportedOperationException("not called in test");
        }

        @Override
        public boolean isRunning(String key) {
            return false;
        }
    };

    private static DistributedRuntimeConfig configReturning(SessionTurnGate gate) {
        return new DistributedRuntimeConfig() {
            @Override
            public io.agentscope.harness.agent.DistributedStore distributedStore() {
                return null;
            }

            @Override
            public SessionTurnGate sessionTurnGate() {
                return gate;
            }

            @Override
            public BaseStore sharedChunkStore() {
                return null;
            }
        };
    }

    @Test
    void inProcessGate_withoutEnforce_logsAdvisoryAndDoesNotThrow() {
        CrossInstanceTurnGateGuardian guardian =
                new CrossInstanceTurnGateGuardian(configReturning(new LocalSessionTurnGate()), false);
        assertDoesNotThrow(() -> guardian.run(null));
    }

    @Test
    void inProcessGate_withEnforce_failClosed() {
        CrossInstanceTurnGateGuardian guardian =
                new CrossInstanceTurnGateGuardian(configReturning(new LocalSessionTurnGate()), true);
        assertThrows(IllegalStateException.class, () -> guardian.run(null));
    }

    @Test
    void crossInstanceGate_neverWarnsOrFails_evenWhenEnforced() {
        CrossInstanceTurnGateGuardian guardian =
                new CrossInstanceTurnGateGuardian(configReturning(CROSS_INSTANCE_GATE), true);
        assertDoesNotThrow(() -> guardian.run(null));
    }
}
