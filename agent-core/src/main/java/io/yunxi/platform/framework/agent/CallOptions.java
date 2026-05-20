package io.yunxi.platform.framework.agent;

import java.time.Duration;
import java.util.Map;

/**
 * 调用选项 — 按需覆盖 Agent 配置
 * <p>
 * 允许在调用时临时覆盖 Agent 的运行时参数，不影响 YAML 配置。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class CallOptions {

    /** Profile 名称 */
    private String profile;

    /** 超时时间覆盖 */
    private Duration timeout;

    /** 是否启用 PlanNotebook 覆盖 */
    private Boolean enablePlan;

    /** 自定义上下文（传递给 PreProcessor） */
    private Map<String, Object> context;

    public CallOptions() {
    }

    public String getProfile() {
        return profile;
    }

    public CallOptions setProfile(String profile) {
        this.profile = profile;
        return this;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public CallOptions setTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public Boolean getEnablePlan() {
        return enablePlan;
    }

    public CallOptions setEnablePlan(Boolean enablePlan) {
        this.enablePlan = enablePlan;
        return this;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public CallOptions setContext(Map<String, Object> context) {
        this.context = context;
        return this;
    }
}