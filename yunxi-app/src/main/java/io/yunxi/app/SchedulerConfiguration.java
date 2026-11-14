package io.yunxi.app;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import io.yunxi.runtime.AgentDefinitionLoader;
import java.util.Properties;
import java.util.UUID;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.utils.C3p0PoolingConnectionProvider;
import org.quartz.utils.DBConnectionManager;
import org.quartz.utils.PoolingConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 调度能力的统一装配入口（唯一装配点）。
 *
 * <p>默认关闭：未显式设置 {@code yunxi.scheduler.enabled=true} 时整个配置类不生效，
 * 不创建 Quartz 调度器、不注册任务、也不要求任何外部存储。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "yunxi.scheduler", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfiguration.class);

    public static final String XXL_JOB_HANDLER = "yunxiScheduledJob";

    /**
     * JDBC JobStore 的所有 SQL 以 {@code SCHED_NAME} 分区，集群内所有节点必须共享
     * 同一个 instanceName、各自 instanceId 唯一；随机 instanceName 会让节点互相不可见。
     */
    static final String CLUSTER_NAME = "yunxi-scheduler-cluster";

    @Bean
    public YunxiSpringBeanJobFactory yunxiSpringBeanJobFactory() {
        return new YunxiSpringBeanJobFactory();
    }

    @Bean(destroyMethod = "shutdown")
    public YunxiAgentScheduler agentScheduler(
            SchedulerProperties properties,
            YunxiSpringBeanJobFactory jobFactory,
            ScheduledJobExecutor executor) throws SchedulerException {
        // schedulerId 只作 JobDataMap 诊断标量，绝不作为 Quartz instanceName。
        String schedulerId = resolveSchedulerId(properties.getSchedulerId());
        if ("xxl-job".equalsIgnoreCase(properties.getBackend())) {
            return new YunxiXxlJobScheduler(executor);
        }
        return new YunxiHarnessAgentScheduler(
                createQuartzScheduler(properties, jobFactory), executor, schedulerId);
    }

    @Bean
    public AgentSchedulerBootstrapper agentSchedulerBootstrapper(
            AgentDefinitionLoader agentDefinitionLoader, YunxiAgentScheduler scheduler) {
        return new AgentSchedulerBootstrapper(agentDefinitionLoader, scheduler);
    }

    /** XXL-JOB executor：admin 不可达或未配置时降级为未启动 Bean，不拖垮应用启动。 */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(prefix = "yunxi.scheduler", name = "backend", havingValue = "xxl-job")
    public XxlJobExecutor xxlJobExecutor(
            SchedulerProperties properties, YunxiAgentScheduler scheduler) {
        XxlJobExecutor executor = new XxlJobExecutor();
        executor.setAdminAddresses(properties.getXxlAdminAddresses());
        executor.setAppname(properties.getXxlAppname());
        executor.setPort(properties.getXxlPort());
        XxlJobExecutor.registryJobHandler(XXL_JOB_HANDLER, new IJobHandler() {
            @Override
            public void execute() {
                XxlJobContext context = XxlJobContext.getXxlJobContext();
                String agentId = context == null ? "" : context.getJobParam();
                if (scheduler instanceof YunxiXxlJobScheduler xxl) {
                    xxl.execute(agentId);
                } else {
                    throw new IllegalStateException("xxl-job backend requires YunxiXxlJobScheduler");
                }
            }
        });
        if (properties.getXxlAdminAddresses() == null
                || properties.getXxlAdminAddresses().isBlank()) {
            log.info("XXL-JOB admin 未配置，executor 保持未启动；handler 已注册");
            return executor;
        }
        try {
            executor.start();
        } catch (Exception e) {
            log.warn("XXL-JOB executor 启动失败，已降级为未启动 Bean：{}", e.getMessage());
        }
        return executor;
    }

    /** 生成唯一诊断标量；与集群 instanceName 无关。 */
    static String resolveSchedulerId(String configuredId) {
        return (configuredId == null || configuredId.isBlank())
                ? "yunxi-scheduler-" + UUID.randomUUID()
                : configuredId;
    }

    private static org.quartz.Scheduler createQuartzScheduler(
            SchedulerProperties properties,
            YunxiSpringBeanJobFactory jobFactory) throws SchedulerException {
        Properties config = new Properties();
        config.setProperty("org.quartz.scheduler.instanceName", CLUSTER_NAME);
        config.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        config.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        config.setProperty("org.quartz.threadPool.threadCount", "4");
        if ("jdbc".equalsIgnoreCase(properties.getStore())) {
            config.setProperty("org.quartz.jobStore.class",
                    "org.quartz.impl.jdbcjobstore.JobStoreTX");
            config.setProperty("org.quartz.jobStore.driverDelegateClass",
                    "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
            config.setProperty("org.quartz.jobStore.tablePrefix", properties.getTablePrefix());
            config.setProperty("org.quartz.jobStore.dataSource", "yunxiDS");
            config.setProperty("org.quartz.jobStore.isClustered", "true");
            // Quartz 2.5.2 的 StdSchedulerFactory 仅能以无参构造器实例化连接池提供器，
            // 而内置 C3p0PoolingConnectionProvider 无无参构造器，走 connectionProvider.class
            // 会抛 NoSuchMethodException。故直接以 6 参构造器创建提供器并注册进 DBConnectionManager，
            // 由 jobStore.dataSource 按名引用，复用 Quartz 原生提供器又绕开其损坏的无参实例化路径。
            PoolingConnectionProvider provider;
            try {
                provider = new C3p0PoolingConnectionProvider(
                        properties.getJdbcDriver(), properties.getJdbcUrl(),
                        properties.getJdbcUser(), properties.getJdbcPassword(), 8, "SELECT 1");
            } catch (java.sql.SQLException e) {
                throw new SchedulerException("创建 C3P0 连接池提供器失败", e);
            }
            DBConnectionManager.getInstance().addConnectionProvider("yunxiDS", provider);
        } else {
            config.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        }

        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(config);
        org.quartz.Scheduler quartz = factory.getScheduler();
        quartz.setJobFactory(jobFactory);
        quartz.start();
        return quartz;
    }
}
