package io.yunxi.app;

import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实现框架 {@link AgentScheduler} 接口的产品平面调度器。
 *
 * <p>触发机制由 Quartz 提供（RAM 或 JDBC JobStore），Job 只携带标量引用，
 * 执行经平台统一门面完成；不依赖框架进程内静态注册表，故任一实例领取 Job 后都能执行。</p>
 */
public class YunxiHarnessAgentScheduler implements YunxiAgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(YunxiHarnessAgentScheduler.class);

    private final org.quartz.Scheduler quartz;
    private final ScheduledJobExecutor executor;
    private final String schedulerId;

    public YunxiHarnessAgentScheduler(
            org.quartz.Scheduler quartz, ScheduledJobExecutor executor, String schedulerId) {
        this.quartz = quartz;
        this.executor = executor;
        this.schedulerId = schedulerId;
    }

    /** 平台内部注册入口：使用只含标量的请求，不经过框架 AgentConfig 建 Agent。 */
    @Override
    public ScheduleAgentTask schedule(ScheduledJobRequest request, ScheduleConfig scheduleConfig) {
        String agentId = request.agentId();
        JobKey jobKey = new JobKey(agentId, ScheduledJobRequest.JOB_GROUP);
        String fingerprint = fingerprintOf(scheduleConfig);
        // 先构造 trigger：非法 cron / mode 在写入 JobStore 之前暴露，避免留下无触发器的孤儿 Job。
        Trigger trigger = buildTrigger(agentId, scheduleConfig);
        JobDetail detail = JobBuilder.newJob(YunxiScheduledJob.class)
                .withIdentity(jobKey)
                .storeDurably(true)
                .setJobData(request.toJobDataMap(schedulerId, fingerprint))
                .build();
        try {
            JobDetail existing = quartz.getJobDetail(jobKey);
            boolean existed = existing != null;
            if (existed
                    && fingerprint.equals(existing.getJobDataMap()
                            .getString(ScheduledJobRequest.KEY_FINGERPRINT))) {
                // 触发配置未变：不重排 trigger，避免滚动重启不断把 next fire time 往后推。
                return viewOf(agentId, existing);
            }
            try {
                if (trigger == null) {
                    quartz.addJob(detail, true, true);
                } else {
                    // 单次原子 upsert：避免 checkExists + scheduleJob 的并发竞态。
                    quartz.scheduleJob(detail, Set.of(trigger), true);
                }
            } catch (Exception failure) {
                // 仅新建失败才清理本次可能落下的孤儿 Job；已有任务保持原状，
                // 避免把一次重排失败升级为健康任务整体下线。
                if (!existed) {
                    rollback(jobKey);
                }
                throw failure;
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to register scheduled job " + agentId, e);
        }
        return new ScheduledTaskView(agentId, request, executor, this::cancel);
    }

    @Override
    public ScheduleAgentTask schedule(AgentConfig agentConfig, ScheduleConfig scheduleConfig) {
        return schedule(
                new ScheduledJobRequest(agentConfig.getName(), null, null, null, null),
                scheduleConfig);
    }

    @Override
    public boolean cancel(String name) {
        try {
            return quartz.deleteJob(new JobKey(name, ScheduledJobRequest.JOB_GROUP));
        } catch (SchedulerException e) {
            log.error("failed to cancel scheduled job {}", name, e);
            return false;
        }
    }

    @Override
    public ScheduleAgentTask getScheduledAgent(String name) {
        try {
            JobDetail detail = quartz.getJobDetail(new JobKey(name, ScheduledJobRequest.JOB_GROUP));
            return detail == null ? null : viewOf(name, detail);
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to read scheduled job " + name, e);
        }
    }

    @Override
    public List<ScheduleAgentTask> getAllScheduleAgentTasks() {
        try {
            Set<JobKey> keys = quartz.getJobKeys(
                    GroupMatcher.jobGroupEquals(ScheduledJobRequest.JOB_GROUP));
            List<ScheduleAgentTask> tasks = new ArrayList<>();
            for (JobKey key : keys) {
                JobDetail detail = quartz.getJobDetail(key);
                if (detail != null) {
                    tasks.add(viewOf(key.getName(), detail));
                }
            }
            return tasks;
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to list scheduled jobs", e);
        }
    }

    @Override
    public void shutdown() {
        try {
            quartz.shutdown();
        } catch (SchedulerException e) {
            log.error("failed to shutdown scheduler {}", schedulerId, e);
        }
    }

    @Override
    public String getSchedulerType() {
        return "yunxi-harness-quartz";
    }

    private void rollback(JobKey jobKey) {
        try {
            quartz.deleteJob(jobKey);
        } catch (Exception cleanup) {
            log.warn("failed to roll back orphan job {}", jobKey, cleanup);
        }
    }

    private ScheduledTaskView viewOf(String name, JobDetail detail) {
        return new ScheduledTaskView(
                name, ScheduledJobRequest.from(detail.getJobDataMap()), executor, this::cancel);
    }

    /** 触发配置指纹：只覆盖触发时机，执行内容变化由 Job 每次读穿透获得，不需重排 trigger。 */
    private static String fingerprintOf(ScheduleConfig config) {
        return String.join("|",
                String.valueOf(config.getScheduleMode()),
                String.valueOf(config.getCronExpression()),
                String.valueOf(config.getFixedRate()),
                String.valueOf(config.getFixedDelay()),
                String.valueOf(config.getInitialDelay()),
                String.valueOf(config.getZoneId()));
    }

    private static Trigger buildTrigger(String name, ScheduleConfig config) {
        long initialDelay = config.getInitialDelay() == null ? 0L : config.getInitialDelay();
        TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(new TriggerKey(name, ScheduledJobRequest.JOB_GROUP))
                .forJob(new JobKey(name, ScheduledJobRequest.JOB_GROUP))
                .startAt(new Date(System.currentTimeMillis() + initialDelay));
        switch (config.getScheduleMode()) {
            case CRON -> {
                String cron = config.getCronExpression();
                if (cron == null || !org.quartz.CronExpression.isValidExpression(cron)) {
                    throw new IllegalArgumentException("invalid cron expression: " + cron);
                }
                builder.withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .inTimeZone(TimeZone.getTimeZone(
                                config.getZoneId() == null ? "GMT" : config.getZoneId())));
            }
            case FIXED_RATE -> builder.withSchedule(SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInMilliseconds(config.getFixedRate())
                    .repeatForever());
            case FIXED_DELAY -> builder.withSchedule(SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInMilliseconds(config.getFixedDelay())
                    .repeatForever());
            case NONE -> {
                return null;
            }
            default -> throw new IllegalArgumentException(
                    "unsupported schedule mode " + config.getScheduleMode());
        }
        return builder.build();
    }
}
