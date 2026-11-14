package io.yunxi.app;

import org.quartz.JobDataMap;

/**
 * 调度注册请求：只含可序列化字符串标量，是 JobDataMap 唯一允许承载的内容。
 *
 * <p>JobDataMap 中的身份 / 租户 / prompt 只作诊断快照；执行时以共享 ConfigCenter
 * 的最新 {@code AgentSpec.ScheduleConfig} 为准，避免定义变更后跨租户越权。</p>
 */
public record ScheduledJobRequest(
        String agentId,
        String tenantId,
        String prompt,
        String triggerUserId,
        String sessionId) {

    static final String KEY_AGENT_ID = "agentId";
    static final String KEY_TENANT_ID = "tenantId";
    static final String KEY_PROMPT = "prompt";
    static final String KEY_TRIGGER_USER_ID = "triggerUserId";
    static final String KEY_SESSION_ID = "sessionId";
    static final String KEY_SCHEDULER_ID = "schedulerId";
    static final String KEY_FINGERPRINT = "configFingerprint";

    static final String JOB_GROUP = "yunxi-scheduler";

    JobDataMap toJobDataMap(String schedulerId, String fingerprint) {
        JobDataMap data = new JobDataMap();
        data.put(KEY_SCHEDULER_ID, nullToEmpty(schedulerId));
        data.put(KEY_AGENT_ID, nullToEmpty(agentId));
        data.put(KEY_TENANT_ID, nullToEmpty(tenantId));
        data.put(KEY_PROMPT, nullToEmpty(prompt));
        data.put(KEY_TRIGGER_USER_ID, nullToEmpty(triggerUserId));
        data.put(KEY_SESSION_ID, nullToEmpty(sessionId));
        data.put(KEY_FINGERPRINT, nullToEmpty(fingerprint));
        return data;
    }

    static ScheduledJobRequest from(JobDataMap data) {
        return new ScheduledJobRequest(
                data.getString(KEY_AGENT_ID),
                emptyToNull(data.getString(KEY_TENANT_ID)),
                data.getString(KEY_PROMPT),
                emptyToNull(data.getString(KEY_TRIGGER_USER_ID)),
                emptyToNull(data.getString(KEY_SESSION_ID)));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
