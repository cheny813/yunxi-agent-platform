package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.impl.StdSchedulerFactory;

/** 注册与回读验证：JobDataMap 只含标量、JobStore 为唯一真相源、幂等、取消与关闭生效。 */
class YunxiHarnessAgentSchedulerTest {

    private org.quartz.Scheduler quartz;
    private YunxiHarnessAgentScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "it-" + UUID.randomUUID());
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "2");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        quartz = factory.getScheduler();
        quartz.start();
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();
        scheduler = new YunxiHarnessAgentScheduler(
                quartz,
                SchedulerTestSupport.executor(SchedulerTestSupport.configCenterWith(), facade),
                "it-scheduler");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!quartz.isShutdown()) {
            quartz.shutdown();
        }
    }

    private static ScheduleConfig cron() {
        return ScheduleConfig.builder().cron("0 0 8 * * ?").build();
    }

    @Test
    void jobDataMapContainsOnlySerializableScalars() throws Exception {
        scheduler.schedule(
                new ScheduledJobRequest("agent-1", "t1", "p", "ops", "s"), cron());

        JobDetail detail = quartz.getJobDetail(
                new JobKey("agent-1", ScheduledJobRequest.JOB_GROUP));
        assertNotNull(detail);
        Set<String> keys = detail.getJobDataMap().keySet();
        assertEquals(Set.of("schedulerId", "agentId", "tenantId", "prompt",
                "triggerUserId", "sessionId", "configFingerprint"), keys);
        detail.getJobDataMap().values().forEach(v ->
                assertTrue(v instanceof String, "JobDataMap 只允许字符串标量，实际：" + v));
    }

    @Test
    void tasksAreReadBackFromJobStoreAndCancelRemovesThem() {
        scheduler.schedule(new ScheduledJobRequest("agent-1", "t1", "p", "ops", "s"), cron());

        assertEquals(1, scheduler.getAllScheduleAgentTasks().size());
        assertEquals("agent-1", scheduler.getAllScheduleAgentTasks().get(0).getName());
        assertNotNull(scheduler.getScheduledAgent("agent-1"));

        assertTrue(scheduler.cancel("agent-1"));
        assertTrue(scheduler.getAllScheduleAgentTasks().isEmpty());
        assertEquals(null, scheduler.getScheduledAgent("agent-1"));
    }

    @Test
    void duplicateRegistrationIsIdempotentAndDoesNotReschedule() throws Exception {
        scheduler.schedule(new ScheduledJobRequest("agent-1", "t1", "p", "ops", "s"), cron());
        var firstTrigger = quartz.getTrigger(
                new org.quartz.TriggerKey("agent-1", ScheduledJobRequest.JOB_GROUP));
        assertNotNull(firstTrigger);

        scheduler.schedule(new ScheduledJobRequest("agent-1", "t1", "p", "ops", "s"), cron());
        var secondTrigger = quartz.getTrigger(
                new org.quartz.TriggerKey("agent-1", ScheduledJobRequest.JOB_GROUP));

        assertEquals(1, scheduler.getAllScheduleAgentTasks().size());
        assertEquals(firstTrigger.getStartTime(), secondTrigger.getStartTime(),
                "指纹未变时不得重排 trigger，否则滚动重启会造成触发饥饿");
    }

    @Test
    void shutdownStopsUnderlyingQuartz() throws Exception {
        scheduler.shutdown();
        assertTrue(quartz.isShutdown(), "shutdown() 须停止底层 Quartz");
        assertThrows(org.quartz.SchedulerException.class, () -> quartz.start(),
                "已 shutdown 的调度器不得被重启");
    }

    @Test
    void schedulerTypeIdentifiesTheBackend() {
        assertEquals("yunxi-harness-quartz", scheduler.getSchedulerType());
    }
}
