package io.yunxi.app;

import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz Job：只从 JobDataMap 读取标量并委托平台执行体。
 *
 * <p>Job 实例由 {@link YunxiSpringBeanJobFactory} 交给 Spring 创建，因而可注入平台 Bean；
 * 不依赖任何进程内静态注册表，任意实例领取后都能独立执行。</p>
 */
public class YunxiScheduledJob implements Job {

    @Autowired
    private ScheduledJobExecutor executor;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap data = context.getJobDetail().getJobDataMap();
        try {
            executor.run(ScheduledJobRequest.from(data));
        } catch (Exception e) {
            throw new JobExecutionException(
                    "scheduled job failed for agentId=" + data.getString(ScheduledJobRequest.KEY_AGENT_ID),
                    e);
        }
    }
}
