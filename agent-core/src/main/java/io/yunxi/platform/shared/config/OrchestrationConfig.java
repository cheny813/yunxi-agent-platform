package io.yunxi.platform.shared.config;

import java.util.List;

/**
 * 编排模式配置 - 取代旧的 BuiltinMode 枚举和 ModeResolver
 * <p>
 * 定义 Agent 的多 Agent 协作模式，支持 supervisor / routing / pipeline / workflow / handoffs / single。
 * 80% 的场景只需配置 pattern 和 experts/stages，框架自动装配。
 * </p>
 *
 * <pre>
 * 配置示例：
 * orchestration:
 *   pattern: "supervisor"
 *   experts:
 *     - name: "dish-searcher"
 *       description: "搜索符合营养标准的菜品"
 * </pre>
 *
 * @author yunxi-agent-platform
 */
public class OrchestrationConfig {

    /**
     * 编排模式
     * <ul>
     *   <li>single - 单 Agent（默认），无协作</li>
     *   <li>supervisor - Supervisor + 专家 Agent，LLM 自主决定调用哪个专家</li>
     *   <li>routing - 路由器分发，由路由器 Agent 分类转发</li>
     *   <li>pipeline - 流水线，按阶段顺序执行</li>
     *   <li>workflow - 自定义工作流（StateGraph）</li>
     *   <li>handoffs - 控制权转移，Agent 可自主转交对话</li>
     * </ul>
     */
    private String pattern = "single";

    /** Supervisor / Routing 模式下，专家 Agent 列表 */
    private List<ExpertConfig> experts;

    /** Pipeline 模式下，执行阶段列表 */
    private List<StageConfig> stages;

    /** Workflow 模式下，状态图定义引用（预留） */
    private String workflowRef;

    /** 是否在发生错误时立即终止（Pipeline 模式默认 true） */
    private Boolean failFast = true;

    public OrchestrationConfig() {
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public List<ExpertConfig> getExperts() {
        return experts;
    }

    public void setExperts(List<ExpertConfig> experts) {
        this.experts = experts;
    }

    public List<StageConfig> getStages() {
        return stages;
    }

    public void setStages(List<StageConfig> stages) {
        this.stages = stages;
    }

    public String getWorkflowRef() {
        return workflowRef;
    }

    public void setWorkflowRef(String workflowRef) {
        this.workflowRef = workflowRef;
    }

    public Boolean getFailFast() {
        return failFast;
    }

    public void setFailFast(Boolean failFast) {
        this.failFast = failFast;
    }
}