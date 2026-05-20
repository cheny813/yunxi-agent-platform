package io.yunxi.platform.shared.config;

/**
 * Pipeline 阶段配置 - Pipeline 模式中的单个执行阶段
 * <p>
 * 每个阶段对应一个 Agent 或一个重试/迭代逻辑。
 * 支持 failFast 语义：任一阶段失败则终止整个 Pipeline。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class StageConfig {

    /** 阶段名称（日志和监控用） */
    private String name;

    /** 阶段描述 */
    private String description;

    /** 负责该阶段的 Agent 名称 */
    private String agent;

    /** 最大重试次数（可选，默认 0 = 不重试） */
    private int maxRetries = 0;

    /** 最大迭代次数（可选，用于 review → fix 循环），默认 1 = 不迭代 */
    private int maxIterations = 1;

    /** 迭代退出条件（可选，如 "pass_rate >= 0.8"），仅在 maxIterations > 1 时有效 */
    private String iterationCondition;

    /** 超时时间（秒），默认 0 = 不限制 */
    private int timeoutSeconds = 0;

    public StageConfig() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public String getIterationCondition() {
        return iterationCondition;
    }

    public void setIterationCondition(String iterationCondition) {
        this.iterationCondition = iterationCondition;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}