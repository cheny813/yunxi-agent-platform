package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** C8 验收：真实由 Quartz 创建 Job 实例时，Spring 注入链路必须生效并抵达执行门面。 */
class YunxiSpringBeanJobFactoryTest {

    @Test
    void quartzCreatedJobIsSpringInjectedAndReachesExecutionFacade() throws Exception {
        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();
        ScheduledJobExecutor executor = SchedulerTestSupport.executor(
                SchedulerTestSupport.configCenterWith(SchedulerTestSupport.spec(
                        "agent-1", SchedulerTestSupport.cron("0 0 8 * * ?"))),
                facade);

        AnnotationConfigApplicationContext springContext = new AnnotationConfigApplicationContext();
        springContext.registerBean(ScheduledJobExecutor.class, () -> executor);
        springContext.refresh();

        YunxiSpringBeanJobFactory jobFactory = new YunxiSpringBeanJobFactory();
        jobFactory.setApplicationContext(springContext);

        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "jobfactory-" + UUID.randomUUID());
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "2");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        org.quartz.Scheduler quartz = factory.getScheduler();
        quartz.setJobFactory(jobFactory);
        quartz.start();

        try {
            YunxiHarnessAgentScheduler scheduler =
                    new YunxiHarnessAgentScheduler(quartz, executor, "jobfactory-it");
            // NONE 模式不建 trigger，只注册 durable Job，随后手动触发以走真实 Quartz Job 创建路径。
            scheduler.schedule(
                    new ScheduledJobRequest("agent-1", "t1", "p", "ops", "s"),
                    ScheduleConfig.builder().build());

            quartz.triggerJob(new JobKey("agent-1", ScheduledJobRequest.JOB_GROUP));
            assertTrue(facade.latch.await(15, TimeUnit.SECONDS),
                    "Quartz 创建的 Job 必须经 Spring 注入 ScheduledJobExecutor 并执行");
            assertEquals(1, facade.commands.size());
            assertEquals("agent-1", facade.commands.get(0).agentId());
        } finally {
            quartz.shutdown();
            springContext.close();
        }
    }
}
