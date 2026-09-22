package io.yunxi.platform.shared.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排模式配置 - 取代旧的 BuiltinMode 枚举和 ModeResolver
 * <p>
 * 定义 Agent 的多 Agent 协作模式。**运行时已接线的模式只有 single 与 supervisor**：
 * 80% 的场景只需配置 pattern 和 experts，框架自动装配。
 * </p>
 * <p>
 * 其余模式的处理：{@code workflow} 是**声明型**（只承载编排图定义，由
 * {@code WorkflowArtifactExporter} 导出后交由 aistio 执行，**不产生 yunxi 运行时 Agent 实例**）；
 * {@code pipeline} / {@code routing} / {@code handoffs} 的调度**不在 yunxi 侧自建**，由
 * {@link AgentDefinitionLoader} 加载期归一到单 Agent（带 mounts 挂载可选）并委托 aistio 控制面调度，
 * 仅当配置无任何实质内容（无 experts / mounts / stages）时才拒绝，避免"配得上、跑不起来"的裸 relabel。
 * </p>
 *
 * <pre>
 * 配置示例：
 * orchestration:
 *   pattern: "supervisor"
 *   experts:
 *     - name: "data-searcher"
 *       description: "检索符合业务标准的数据"
 * </pre>
 *
 * @author yunxi-agent-platform
 */
public class OrchestrationConfig {

    /**
     * 编排模式（大小写不敏感）
     * <ul>
     *   <li>single - 单 Agent（默认），无协作 —— 运行时已接线</li>
     *   <li>supervisor - Supervisor + 专家 Agent，LLM 自主决定调用哪个专家 —— 运行时已接线</li>
     *   <li>workflow - 声明型：编排图定义载体，仅供导出，不产生运行时实例</li>
     * </ul>
     * pipeline / routing / handoffs 不在 yunxi 侧自建运行时，加载期归一到单 Agent 并委托 aistio 控制面
 * 调度，仅当无 experts / mounts / stages 等任何实质内容时才被 {@link AgentDefinitionLoader} 拒绝。
     */
    private String pattern = "single";

    /**
     * 是否为声明型编排：只承载编排图定义供导出，不产生运行时 Agent 实例。
     *
     * @return pattern 为 workflow 时返回 true
     */
    public boolean isDeclarationOnly() {
        return "workflow".equalsIgnoreCase(pattern);
    }

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

    /**
     * 挂载定义列表：声明可供触发编排的入口。
     * <p>绑定 YAML 路径 {@code orchestration.mounts}。其中 workflow 类型且内联 graph 的挂载，
     * 会由 translator 校验并导出 aistio DefinitionSpec artifact 供控制台导入。</p>
     */
    private List<MountConfig> mounts = new ArrayList<>();

    /**
     * 顶层编排图定义：当某个 workflow 挂载以 definition key 引用、而非内联 graph 时，
     * 该 key 应对应此处声明的图。绑定 YAML 路径 {@code orchestration.graph}。
     */
    private GraphConfig graph;

    public List<MountConfig> getMounts() {
        return mounts;
    }

    public void setMounts(List<MountConfig> mounts) {
        this.mounts = mounts;
    }

    public GraphConfig getGraph() {
        return graph;
    }

    public void setGraph(GraphConfig graph) {
        this.graph = graph;
    }
}