package io.yunxi.platform.shared.config;

import java.time.Duration;

/**
 * 运行时配置 - Agent 执行参数
 * <p>
 * 控制 Agent 的最大迭代次数、超时时间等运行时行为。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class RuntimeConfig {

    /** 最大迭代次数（ReAct 循环上限） */
    private int maxIterations = 30;

    /** 单次调用超时时间 */
    private Duration timeout = Duration.ofSeconds(120);

    /** 是否启用 MetaTool（LLM 动态切换工具组） */
    private boolean enableMetaTool = true;

    public RuntimeConfig() {
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isEnableMetaTool() {
        return enableMetaTool;
    }

    public void setEnableMetaTool(boolean enableMetaTool) {
        this.enableMetaTool = enableMetaTool;
    }
}