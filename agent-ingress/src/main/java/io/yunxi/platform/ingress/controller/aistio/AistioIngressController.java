package io.yunxi.platform.ingress.controller.aistio;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.yunxi.platform.contract.DataPlaneContract;
import io.yunxi.platform.contract.dto.ContextSnapshot;
import io.yunxi.platform.contract.dto.DataPlaneInfo;
import io.yunxi.platform.contract.dto.HealthResponse;
import io.yunxi.platform.contract.dto.MessagePage;
import io.yunxi.platform.contract.dto.PlanModeRequest;
import io.yunxi.platform.contract.dto.SessionSnapshot;
import io.yunxi.platform.contract.dto.SessionState;
import io.yunxi.platform.contract.dto.SubagentInfo;
import io.yunxi.platform.contract.dto.TaskInfo;
import io.yunxi.platform.contract.dto.WorkspaceInfo;
import lombok.RequiredArgsConstructor;

/**
 * aistio 契约端点（基址 {@code /agentscope}），收编自 {@code agent-aistio} 的 {@code AistioContractController}。
 *
 * <p>本控制器是「统一入口网关」的一部分，仅依赖 {@code agent-core} 的 {@link DataPlaneContract} 接口，
 * 对 {@code agent-aistio} 保持编译隔离（铁律：入口层不依赖 aistio 模块）。实现由 {@code agent-aistio}
 * 的 {@code AistioDataPlaneContract} 提供；当 aistio 未启用（或模块未引入）时，{@link DataPlaneContract}
 * 实例经 {@link ObjectProvider} 取不到，所有端点统一返回 503，避免硬失败。</p>
 *
 * <p>仅当 {@code yunxi.aistio.enabled=true} 时装配。</p>
 */
@RestController
@RequestMapping("/agentscope")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yunxi.aistio.enabled", havingValue = "true")
public class AistioIngressController {

    private static final Logger log = LoggerFactory.getLogger(AistioIngressController.class);

    private final ObjectProvider<DataPlaneContract> contractProvider;

    private DataPlaneContract requireContract() {
        DataPlaneContract contract = contractProvider.getIfAvailable();
        if (contract == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "aistio data-plane contract is not available (yunxi.aistio.enabled=false or module absent)");
        }
        return contract;
    }

    /** 数据面信息（含能力声明 + MCP 工具名，验收 /verify 用）。 */
    @GetMapping("/info")
    public DataPlaneInfo info() {
        return requireContract().info();
    }

    /** 健康检查。 */
    @GetMapping("/health")
    public HealthResponse health() {
        return requireContract().health();
    }

    /** 会话清单。 */
    @GetMapping("/sessions")
    public List<SessionSnapshot> listSessions() {
        return requireContract().listSessions();
    }

    /** 会话运行状态（含 plan-mode 实时状态）。 */
    @GetMapping("/sessions/{id}/state")
    public SessionState sessionState(@PathVariable("id") String id) {
        return requireContract().getState(id);
    }

    /** 会话有效上下文窗口。 */
    @GetMapping("/sessions/{id}/context")
    public ContextSnapshot context(@PathVariable("id") String id) {
        return requireContract().getContext(id);
    }

    /** 会话完整历史分页。 */
    @GetMapping("/sessions/{id}/messages")
    public MessagePage messages(@PathVariable("id") String id,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return requireContract().getMessages(id, offset, limit);
    }

    /** 子智能体编排树（subagent-inventory）。 */
    @GetMapping("/subagents")
    public List<SubagentInfo> subagents() {
        return requireContract().getTopology();
    }

    /** 工作空间清单（workspace-inventory）。 */
    @GetMapping("/workspaces")
    public List<WorkspaceInfo> workspaces() {
        return requireContract().getWorkspaces();
    }

    /** 任务清单（task-query）。 */
    @GetMapping("/sessions/{id}/tasks")
    public List<TaskInfo> tasks(@PathVariable("id") String id) {
        return requireContract().listTasks(id);
    }

    /** 子任务清单（task-query，与 /tasks 同源，粒度更细）。 */
    @GetMapping("/sessions/{id}/subagent-tasks")
    public List<TaskInfo> subagentTasks(@PathVariable("id") String id) {
        return requireContract().listTasks(id);
    }

    /** 强制压缩会话上下文（session-command）。 */
    @PostMapping("/sessions/{id}/compress")
    public Map<String, Object> compress(@PathVariable("id") String id) {
        return requireContract().compress(id);
    }

    /** 终止 / 清理会话（session-command）。 */
    @PostMapping("/sessions/{id}/terminate")
    public Map<String, Object> terminate(@PathVariable("id") String id) {
        return requireContract().terminate(id);
    }

    /** 切换会话 plan-mode（plan-mode 能力）。 */
    @PostMapping("/sessions/{id}/plan-mode")
    public Map<String, Object> planMode(@PathVariable("id") String id,
            @RequestBody(required = false) PlanModeRequest req) {
        boolean enabled = req != null && req.isEnabled();
        return requireContract().setPlanMode(id, enabled);
    }

    /** 取消指定子任务（task-query）。 */
    @DeleteMapping("/sessions/{id}/subagent-tasks/{taskId}")
    public Map<String, Object> cancelTask(@PathVariable("id") String id,
            @PathVariable("taskId") String taskId) {
        return requireContract().cancelTask(id, taskId);
    }

    /** 接纳 aistio 团队加入请求（team-coordination 能力）。 */
    @PostMapping("/teams/join")
    public Map<String, Object> joinTeam(@RequestBody(required = false) Map<String, Object> payload) {
        return requireContract().joinTeam(payload);
    }
}
