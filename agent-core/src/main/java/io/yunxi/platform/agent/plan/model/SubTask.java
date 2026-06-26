package io.yunxi.platform.agent.plan.model;

/**
 * 本地子任务模型。
 *
 * <p>
 * V2.0-RC3: 框架已将 {@code io.agentscope.core.plan.model.SubTask} 完全删除。
 * yunxi 保留此本地 SubTask 模型以支持 YAML 模板匹配功能。
 * </p>
 */
public class SubTask {
    private final String name;
    private final String description;
    private final String expectedOutcome;

    public SubTask(String name, String description, String expectedOutcome) {
        this.name = name;
        this.description = description;
        this.expectedOutcome = expectedOutcome;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getExpectedOutcome() { return expectedOutcome; }
}
