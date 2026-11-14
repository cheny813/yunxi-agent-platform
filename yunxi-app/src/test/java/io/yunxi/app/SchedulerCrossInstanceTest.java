package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.utils.C3p0PoolingConnectionProvider;
import org.quartz.utils.DBConnectionManager;
import org.quartz.utils.PoolingConnectionProvider;

/** 跨实例验证：领取方只依赖 JobStore 中的标量，不依赖注册方的进程内状态。 */
class SchedulerCrossInstanceTest {

    private final List<org.quartz.Scheduler> created = new ArrayList<>();

    @AfterEach
    void shutdownAll() throws Exception {
        for (org.quartz.Scheduler scheduler : created) {
            if (!scheduler.isShutdown()) {
                scheduler.shutdown();
            }
        }
    }

    private org.quartz.Scheduler newScheduler(String label, boolean jdbc) throws Exception {
        Properties props = new Properties();
        // JDBC JobStore 以 SCHED_NAME 分区：集群成员必须共享 instanceName、各自 instanceId 唯一。
        props.setProperty("org.quartz.scheduler.instanceName",
                jdbc ? SchedulerConfiguration.CLUSTER_NAME : label + "-" + UUID.randomUUID());
        props.setProperty("org.quartz.scheduler.instanceId", label + "-" + UUID.randomUUID());
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "2");
        if (jdbc) {
            props.setProperty("org.quartz.jobStore.class",
                    "org.quartz.impl.jdbcjobstore.JobStoreTX");
            props.setProperty("org.quartz.jobStore.driverDelegateClass",
                    "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
            props.setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_");
            props.setProperty("org.quartz.jobStore.dataSource", "itDS");
            props.setProperty("org.quartz.jobStore.isClustered", "true");
            props.setProperty("org.quartz.jobStore.clusterCheckinInterval", "5000");
            // Quartz 2.5.2 的 StdSchedulerFactory 仅能以无参构造器实例化连接池提供器，
            // 而内置 C3p0PoolingConnectionProvider 无无参构造器，走 connectionProvider.class
            // 会抛 NoSuchMethodException。故直接用其 (driver,URL,user,password,max,validation)
            // 构造器创建并提供器，注册进 DBConnectionManager，由 jobStore.dataSource 按名引用，
            // 既复用 Quartz 原生提供器又绕开其损坏的无参实例化路径（生产 createQuartzScheduler 同此）。
            props.setProperty("yunxi.jdbc.driver", "com.mysql.cj.jdbc.Driver");
            props.setProperty("yunxi.jdbc.url", System.getProperty("MYSQL_JDBC_URL",
                    "jdbc:mysql://127.0.0.1:3306/yunxi_agent_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"));
            props.setProperty("yunxi.jdbc.user", System.getProperty("MYSQL_USER", "root"));
            props.setProperty("yunxi.jdbc.password", System.getProperty("MYSQL_PASSWORD", "root"));
            props.setProperty("yunxi.jdbc.maxConnections", "4");
            registerJdbcDataSourceProvider(props);
        } else {
            props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        }
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        org.quartz.Scheduler scheduler = factory.getScheduler();
        created.add(scheduler);
        return scheduler;
    }

    /**
     * 直接以 Quartz 内置 {@link C3p0PoolingConnectionProvider} 的 6 参构造器创建连接池提供器，
     * 注册进 {@link DBConnectionManager} 后由 {@code org.quartz.jobStore.dataSource} 按名引用。
     * 用于绕开 StdSchedulerFactory 在 Quartz 2.5.2 下只支持无参实例化连接池提供器的限制
     * （内置提供器均无无参构造器）。
     */
    private void registerJdbcDataSourceProvider(Properties props) throws Exception {
        String dsName = props.getProperty("org.quartz.jobStore.dataSource");
        PoolingConnectionProvider provider = new C3p0PoolingConnectionProvider(
                props.getProperty("yunxi.jdbc.driver"),
                props.getProperty("yunxi.jdbc.url"),
                props.getProperty("yunxi.jdbc.user"),
                props.getProperty("yunxi.jdbc.password"),
                Integer.parseInt(props.getProperty("yunxi.jdbc.maxConnections", "4")),
                "SELECT 1");
        DBConnectionManager.getInstance().addConnectionProvider(dsName, provider);
    }

    private static ScheduleConfig cron() {
        return ScheduleConfig.builder().cron("0 0 8 * * ?").build();
    }

    @Test
    void anotherInstanceReadsAndExecutesFromSharedJobStore() throws Exception {
        org.quartz.Scheduler shared = newScheduler("shared", false);
        shared.start();

        SchedulerTestSupport.StubExecutionFacade facadeA = new SchedulerTestSupport.StubExecutionFacade();
        SchedulerTestSupport.StubExecutionFacade facadeB = new SchedulerTestSupport.StubExecutionFacade();
        YunxiHarnessAgentScheduler instanceA = new YunxiHarnessAgentScheduler(
                shared,
                SchedulerTestSupport.executor(SchedulerTestSupport.configCenterWith(), facadeA),
                "instance-a");
        YunxiHarnessAgentScheduler instanceB = new YunxiHarnessAgentScheduler(
                shared,
                SchedulerTestSupport.executor(SchedulerTestSupport.configCenterWith(
                        SchedulerTestSupport.spec("agent-1", SchedulerTestSupport.cron("0 0 8 * * ?"))),
                        facadeB),
                "instance-b");

        instanceA.schedule(new ScheduledJobRequest("agent-1", "t1", "p", "ops", "s"), cron());

        var task = instanceB.getScheduledAgent("agent-1");
        assertNotNull(task, "领取方必须能从共享 JobStore 回读任务");
        assertNotNull(task.run().block());
        assertEquals(1, facadeB.commands.size(), "执行须发生在领取方");
        assertTrue(facadeA.commands.isEmpty(), "不得依赖注册方的进程内状态");
        assertEquals("ops", facadeB.commands.get(0).userId());
    }

    /**
     * 真实 JDBC JobStore：需由部署流程先初始化 QRTZ_* 表（deploy/infra/init/quartz-tables.sql），
     * 并显式开启 {@code -Dyunxi.scheduler.jdbc.it=true} 才运行。两个 JDBC 实例共享
     * {@code SCHED_NAME} 分区（SchedulerConfiguration.CLUSTER_NAME）与同一 JobStore，
     * 验证任务经持久化跨实例可见。
     */
    @Test
    @EnabledIfSystemProperty(named = "yunxi.scheduler.jdbc.it", matches = "true")
    void jdbcJobStorePersistsJobAcrossIndependentSchedulers() throws Exception {
        org.quartz.Scheduler instanceA = newScheduler("jdbc-a", true);
        org.quartz.Scheduler instanceB = newScheduler("jdbc-b", true);
        instanceA.start();
        instanceB.start();

        SchedulerTestSupport.StubExecutionFacade facade = new SchedulerTestSupport.StubExecutionFacade();
        YunxiHarnessAgentScheduler registrar = new YunxiHarnessAgentScheduler(
                instanceA,
                SchedulerTestSupport.executor(SchedulerTestSupport.configCenterWith(), facade),
                "jdbc-a");
        YunxiHarnessAgentScheduler receiver = new YunxiHarnessAgentScheduler(
                instanceB,
                SchedulerTestSupport.executor(SchedulerTestSupport.configCenterWith(
                        SchedulerTestSupport.spec("agent-jdbc", SchedulerTestSupport.cron("0 0 8 * * ?"))),
                        facade),
                "jdbc-b");

        registrar.schedule(new ScheduledJobRequest("agent-jdbc", "t1", "p", "ops", "s"), cron());

        var task = receiver.getScheduledAgent("agent-jdbc");
        assertNotNull(task, "JDBC JobStore 须让另一实例看到已持久化的任务");
        assertNotNull(task.run().block());
        assertEquals(1, facade.commands.size());
        assertTrue(receiver.cancel("agent-jdbc"));
    }
}
