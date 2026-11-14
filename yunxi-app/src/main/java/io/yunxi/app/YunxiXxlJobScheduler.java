package io.yunxi.app;

import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * XXL-JOB 后端：任务定义只存标量请求，触发由外部调度中心按 agentId 回调。
 *
 * <p>本类不持有 Agent 配置副本；{@link #execute(String)} 是 handler / 控制器共用的触发入口，
 * 与 Quartz 后端共用同一个 {@link ScheduledJobExecutor}，因此执行语义一致。</p>
 */
public class YunxiXxlJobScheduler implements YunxiAgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(YunxiXxlJobScheduler.class);

    private final Map<String, ScheduledJobRequest> requests = new ConcurrentHashMap<>();
    private final ScheduledJobExecutor executor;

    public YunxiXxlJobScheduler(ScheduledJobExecutor executor) {
        this.executor = executor;
    }

    @Override
    public ScheduleAgentTask schedule(ScheduledJobRequest request, ScheduleConfig scheduleConfig) {
        // 触发时机由 XXL-JOB admin 侧任务配置管理；本后端只承担「按 agentId 回调」通道。
        log.info("XXL-JOB 回调通道注册 agentId={} mode={}（触发时机由 admin 侧配置）",
                request.agentId(), scheduleConfig.getScheduleMode());
        requests.put(request.agentId(), request);
        return new ScheduledTaskView(request.agentId(), request, executor, requests::remove);
    }

    @Override
    public ScheduleAgentTask schedule(AgentConfig agentConfig, ScheduleConfig scheduleConfig) {
        return schedule(
                new ScheduledJobRequest(agentConfig.getName(), null, null, null, null),
                scheduleConfig);
    }

    /** XXL-JOB handler 触发入口；定义缺失时由执行体明确抛错，不静默返回。 */
    public void execute(String agentId) {
        ScheduledJobRequest request = requests.get(agentId);
        if (request == null) {
            throw new IllegalArgumentException("no scheduled request registered for " + agentId);
        }
        executor.run(request);
    }

    @Override
    public boolean cancel(String name) {
        return requests.remove(name) != null;
    }

    @Override
    public ScheduleAgentTask getScheduledAgent(String name) {
        ScheduledJobRequest request = requests.get(name);
        return request == null
                ? null
                : new ScheduledTaskView(name, request, executor, requests::remove);
    }

    @Override
    public List<ScheduleAgentTask> getAllScheduleAgentTasks() {
        List<ScheduleAgentTask> tasks = new ArrayList<>();
        requests.forEach((name, request) ->
                tasks.add(new ScheduledTaskView(name, request, executor, requests::remove)));
        return tasks;
    }

    @Override
    public void shutdown() {
        log.info("XXL-JOB scheduler backend shutting down, clearing {} requests", requests.size());
        requests.clear();
    }

    @Override
    public String getSchedulerType() {
        return "yunxi-xxl-job";
    }
}
