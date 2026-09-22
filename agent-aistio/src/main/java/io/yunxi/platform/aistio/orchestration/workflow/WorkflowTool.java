package io.yunxi.platform.aistio.orchestration.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.platform.aistio.orchestration.OrchestrationTrigger;
import io.yunxi.platform.aistio.orchestration.TriggerReceipt;
import reactor.core.publisher.Mono;

/**
 * 工作流触发工具（通道②挂载产物）：把「触发 aistio 编排运行」暴露为 Agent 可调用的工具。
 *
 * <p>三件套（同一实例承载，经 {@code toolkit.registration().tool(this)} 注册后框架扫描全部 {@code @Tool} 方法）：
 * <ul>
 *   <li>{@code workflow_start} —— 触发一次编排运行（唯一路径：automation webhook）；</li>
 *   <li>{@code workflow_status} —— 按关联 ID 查询本地运行状态（触发侧视角，真相源在服务端）；</li>
 *   <li>{@code workflow_cancel} —— 标记取消意图（执行侧是否据此中断属后续运行可观测范围）。</li>
 * </ul>
 *
 * <p>权限边界：本工具只做「触发」，不做「读 / 控」—— 编排资源对数据面内网 token 403，
 * 触发侧拿不到 orchestrationRunId（webhook 只回 delivery）。故 start 后由触发侧把关联信息写入
 * {@link WorkflowRunStateStore}（左移写入，不回读服务端），status 仅返回本地镜像。</p>
 */
public class WorkflowTool {

    private final String definitionKey;
    private final OrchestrationTrigger trigger;
    private final WorkflowRunStateStore runStateStore;

    public WorkflowTool(String definitionKey, OrchestrationTrigger trigger, WorkflowRunStateStore runStateStore) {
        this.definitionKey = definitionKey;
        this.trigger = trigger;
        this.runStateStore = runStateStore;
    }

    @Tool(name = "workflow_start",
            description = "触发一次工作流运行。input 原样作为编排运行入参；返回触发回执（状态 / 运行ID / 关联ID）。"
                    + "同一逻辑请求重试时传同一个 biz_request_id 以保证幂等。")
    public Map<String, Object> start(
            @ToolParam(name = "input", description = "编排运行入参（原样传递给编排定义）") Map<String, Object> input,
            @ToolParam(name = "biz_request_id",
                    description = "业务请求ID；同一逻辑请求重试时传同一个值以保证幂等，可留空由系统生成") String bizRequestId) {
        String biz = (bizRequestId == null || bizRequestId.isBlank()) ? UUID.randomUUID().toString() : bizRequestId;
        try {
            TriggerReceipt receipt = trigger.start(definitionKey, input, biz).block();
            if (receipt != null) {
                runStateStore.markTriggered(receipt.correlationId(), receipt.automationRunId(), definitionKey, biz);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", receipt.status());
                result.put("automationRunId", receipt.automationRunId());
                result.put("correlationId", receipt.correlationId());
                return result;
            }
            return Map.of("status", "error", "message", "触发无回执");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @Tool(name = "workflow_status",
            description = "查询工作流运行状态（按关联ID）。返回 triggered / accepted / cancellation_requested 或 unknown"
                    + "（本地状态缺失，真相源在服务端）。")
    public Map<String, Object> status(
            @ToolParam(name = "correlation_id", description = "触发时返回的关联ID") String correlationId) {
        WorkflowRunState s = runStateStore.get(correlationId);
        if (s == null) {
            return Map.of("status", "unknown", "message", "本地无状态（可能未触发或进程已重启；真相源在服务端）");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("phase", s.phase());
        r.put("runId", s.runId());
        r.put("correlationId", s.correlationId());
        return r;
    }

    @Tool(name = "workflow_cancel",
            description = "请求取消工作流运行（按关联ID标记取消意图；执行侧是否据此中断属后续运行可观测范围）。")
    public Map<String, Object> cancel(
            @ToolParam(name = "correlation_id", description = "触发时返回的关联ID") String correlationId) {
        runStateStore.markCancellationRequested(correlationId);
        return Map.of("status", "cancellation_requested", "correlationId", correlationId);
    }
}
