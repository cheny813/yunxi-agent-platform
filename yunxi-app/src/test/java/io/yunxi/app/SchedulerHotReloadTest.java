package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.DefaultAgentSpecValidator;
import io.yunxi.runtime.AgentDefinitionLoader;
import org.junit.jupiter.api.Test;

/**
 * 重注册与热更新边界：执行内容变更由 Job 读穿透生效（见 YunxiScheduledJobTest）；
 * 触发时机变更由显式重注册按配置指纹重排，且重复注册幂等。
 */
class SchedulerHotReloadTest {

    @Test
    void explicitReRegistrationIsIdempotentAndRefreshesDiagnosticSnapshot() {
        InMemoryConfigCenter center = SchedulerTestSupport.configCenterWith(
                SchedulerTestSupport.spec("agent-1", SchedulerTestSupport.cron("0 0 8 * * ?")));
        AgentDefinitionLoader loader = new AgentDefinitionLoader(center, new DefaultAgentSpecValidator());
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();
        YunxiXxlJobScheduler scheduler = new YunxiXxlJobScheduler(
                SchedulerTestSupport.executor(center, facade));
        AgentSchedulerBootstrapper bootstrapper = new AgentSchedulerBootstrapper(loader, scheduler);

        bootstrapper.register();
        assertEquals(1, scheduler.getAllScheduleAgentTasks().size());

        center.registerAgentSpec(SchedulerTestSupport.spec("agent-1",
                new AgentSpec.ScheduleConfig(
                        "CRON", "0 0 9 * * ?", null, null, null, "UTC",
                        "new-prompt", "new-user", "t2")));
        bootstrapper.register();

        assertEquals(1, scheduler.getAllScheduleAgentTasks().size(), "同 agentId 幂等 upsert");
        ScheduledTaskView view = (ScheduledTaskView) scheduler.getScheduledAgent("agent-1");
        assertEquals("t2", view.request().tenantId(), "重注册须刷新诊断快照");
    }

    @Test
    void invalidDefinitionIsSkippedWithoutBlockingValidOne() {
        AgentSpec invalid = SchedulerTestSupport.spec("agent-bad",
                new AgentSpec.ScheduleConfig("FIXED_RATE", null, 1L, null, null, null, "p", "ops"));
        InMemoryConfigCenter center = SchedulerTestSupport.configCenterWith(
                invalid, SchedulerTestSupport.spec("agent-good", SchedulerTestSupport.cron("0 0 8 * * ?")));
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();
        YunxiXxlJobScheduler scheduler = new YunxiXxlJobScheduler(
                SchedulerTestSupport.executor(center, facade));

        new AgentSchedulerBootstrapper(
                new AgentDefinitionLoader(center, new DefaultAgentSpecValidator()), scheduler)
                .register();

        assertEquals(1, scheduler.getAllScheduleAgentTasks().size());
        assertEquals("agent-good", scheduler.getAllScheduleAgentTasks().get(0).getName());
    }

    @Test
    void xxlJobHandlerFailsExplicitlyForUnknownAgent() {
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();
        YunxiXxlJobScheduler scheduler = new YunxiXxlJobScheduler(
                SchedulerTestSupport.executor(SchedulerTestSupport.configCenterWith(), facade));

        assertThrows(IllegalArgumentException.class, () -> scheduler.execute("unknown"));
    }
}
