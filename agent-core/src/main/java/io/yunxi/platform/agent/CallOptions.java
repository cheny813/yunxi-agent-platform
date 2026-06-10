package io.yunxi.platform.agent;

import java.time.Duration;
import java.util.Map;

/**
 * Agent 调用选项，控制单次调用的行为。
 *
 * <p>
 * 可通过 Builder 或 setter 链式设置，也可通过 YAML 配置默认值。
 * 每次调用 Agent 时可传入不同的 CallOptions 实例，
 * 实现对超时、Profile 选择、规划开关等行为的精细控制。
 * </p>
 */
public class CallOptions {

    /** Agent Profile 标识，用于选择 Agent 的角色配置（如模型、Prompt 等） */
    private String profile;

    /** 调用超时时间，超过此时间未完成则中断调用 */
    private Duration timeout;

    /** 是否启用 Plan 规划模式，启用后 Agent 会先制定计划再执行 */
    private Boolean enablePlan;

    /** 调用上下文，用于传递额外的业务参数到 Agent 执行环境 */
    private Map<String, Object> context;

    /**
     * 默认构造函数。
     */
    public CallOptions() {
    }

    /**
     * 获取 Agent Profile 标识。
     *
     * @return Profile 名称，可能为 null（使用默认 Profile）
     */
    public String getProfile() {
        return profile;
    }

    /**
     * 设置 Agent Profile 标识，支持链式调用。
     *
     * @param profile Profile 名称
     * @return 当前 CallOptions 实例
     */
    public CallOptions setProfile(String profile) {
        this.profile = profile;
        return this;
    }

    /**
     * 获取调用超时时间。
     *
     * @return 超时时间，可能为 null（使用系统默认超时）
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * 设置调用超时时间，支持链式调用。
     *
     * @param timeout 超时时间
     * @return 当前 CallOptions 实例
     */
    public CallOptions setTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * 获取是否启用 Plan 规划模式。
     *
     * @return true 表示启用规划模式，null 表示使用配置默认值
     */
    public Boolean getEnablePlan() {
        return enablePlan;
    }

    /**
     * 设置是否启用 Plan 规划模式，支持链式调用。
     *
     * @param enablePlan true 启用，false 禁用
     * @return 当前 CallOptions 实例
     */
    public CallOptions setEnablePlan(Boolean enablePlan) {
        this.enablePlan = enablePlan;
        return this;
    }

    /**
     * 获取调用上下文。
     *
     * @return 上下文 Map，可能为 null
     */
    public Map<String, Object> getContext() {
        return context;
    }

    /**
     * 设置调用上下文，支持链式调用。
     *
     * @param context 业务参数 Map
     * @return 当前 CallOptions 实例
     */
    public CallOptions setContext(Map<String, Object> context) {
        this.context = context;
        return this;
    }
}
