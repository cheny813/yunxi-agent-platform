package io.yunxi.app;

import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;

/** 平台调度后端共用契约：以标量请求注册，不经过框架 AgentConfig 建 Agent。 */
public interface YunxiAgentScheduler extends AgentScheduler {

    ScheduleAgentTask schedule(ScheduledJobRequest request, ScheduleConfig scheduleConfig);
}
