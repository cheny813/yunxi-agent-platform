package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.AgentCommand;
import io.yunxi.runtime.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 触发执行验证：统一门面、最新定义的身份与租户、读穿透与定义缺失显式失败。 */
class YunxiScheduledJobTest {

    private static final ScheduledJobRequest STALE_SNAPSHOT =
            new ScheduledJobRequest("agent-1", "old-tenant", "old-prompt", "old-user", "old-session");

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static InMemoryConfigCenter centerWith(AgentSpec.ScheduleConfig schedule) {
        return SchedulerTestSupport.configCenterWith(
                SchedulerTestSupport.spec("agent-1", schedule));
    }

    @Test
    void executesThroughExecutionFacadeUsingLatestDefinitionValues() {
        InMemoryConfigCenter center = centerWith(SchedulerTestSupport.cron("0 0 8 * * ?"));
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();

        SchedulerTestSupport.executor(center, facade).run(STALE_SNAPSHOT);

        assertEquals(1, facade.commands.size());
        AgentCommand command = facade.commands.get(0);
        assertEquals("agent-1", command.agentId());
        assertEquals("ops", command.userId(), "身份须取最新定义，而非注册时快照");
        assertEquals("scheduled:agent-1", command.sessionId());
        assertEquals("scheduled", command.intent());
        assertEquals("daily", command.messages().get(0).getTextContent());
    }

    @Test
    void tenantIsBoundDuringExecutionAndClearedAfterwards() {
        InMemoryConfigCenter center = centerWith(SchedulerTestSupport.cron("0 0 8 * * ?"));
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();

        SchedulerTestSupport.executor(center, facade).run(STALE_SNAPSHOT);

        assertEquals("t1", facade.tenants.get(0), "订阅线程须看到最新定义中的租户");
        assertNull(TenantContext.current(), "收尾须清除 ThreadLocal");
    }

    @Test
    void missingDefinitionFailsExplicitly() {
        InMemoryConfigCenter center = SchedulerTestSupport.configCenterWith();
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();

        assertThrows(RuntimeException.class, () -> SchedulerTestSupport.executor(center, facade)
                .run(STALE_SNAPSHOT));
        assertTrue(facade.commands.isEmpty(), "定义缺失时不得执行 Agent");
    }

    @Test
    void definitionWithoutScheduleFailsExplicitly() {
        InMemoryConfigCenter center = centerWith(null);
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();

        assertThrows(IllegalStateException.class, () -> SchedulerTestSupport.executor(center, facade)
                .run(STALE_SNAPSHOT));
    }

    @Test
    void definitionIsReadThroughOnEveryTrigger() {
        InMemoryConfigCenter center = centerWith(SchedulerTestSupport.cron("0 0 8 * * ?"));
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();
        ScheduledJobExecutor executor = SchedulerTestSupport.executor(center, facade);

        executor.run(STALE_SNAPSHOT);
        center.removeAgentSpec("agent-1");
        assertThrows(RuntimeException.class, () -> executor.run(STALE_SNAPSHOT),
                "每次触发都须回读共享定义；缓存副本会掩盖定义下线");
        assertEquals(1, facade.commands.size());
    }

    @Test
    void definitionChangeTakesEffectOnNextTriggerWithoutRebuildingJob() {
        InMemoryConfigCenter center = centerWith(SchedulerTestSupport.cron("0 0 8 * * ?"));
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();
        ScheduledJobExecutor executor = SchedulerTestSupport.executor(center, facade);

        executor.run(STALE_SNAPSHOT);
        center.registerAgentSpec(SchedulerTestSupport.spec("agent-1",
                new AgentSpec.ScheduleConfig(
                        "CRON", "0 0 9 * * ?", null, null, null, "UTC",
                        "new-prompt", "new-user", "t2")));
        executor.run(STALE_SNAPSHOT);

        assertEquals(2, facade.commands.size());
        assertEquals("new-user", facade.commands.get(1).userId());
        assertEquals("new-prompt", facade.commands.get(1).messages().get(0).getTextContent());
        assertEquals("t2", facade.tenants.get(1), "租户变更不得沿用注册时快照");
    }
}
