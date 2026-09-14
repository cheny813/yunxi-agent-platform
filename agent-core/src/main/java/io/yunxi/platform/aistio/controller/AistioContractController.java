package io.yunxi.platform.aistio.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.aistio.dto.ContextSnapshot;
import io.yunxi.platform.aistio.dto.DataPlaneInfo;
import io.yunxi.platform.aistio.dto.HealthResponse;
import io.yunxi.platform.aistio.dto.MessagePage;
import io.yunxi.platform.aistio.dto.PlanModeRequest;
import io.yunxi.platform.aistio.dto.SessionSnapshot;
import io.yunxi.platform.aistio.dto.SessionState;
import io.yunxi.platform.aistio.dto.SubagentInfo;
import io.yunxi.platform.aistio.dto.TaskInfo;
import io.yunxi.platform.aistio.dto.WorkspaceInfo;
import io.yunxi.platform.aistio.registry.ActiveSessionRegistry;
import io.yunxi.platform.aistio.service.AistioInfoService;
import io.yunxi.platform.aistio.service.GovernanceService;
import io.yunxi.platform.aistio.service.SessionInventoryService;
import io.yunxi.platform.aistio.service.SubagentTopologyService;
import io.yunxi.platform.aistio.service.WorkspaceInventoryService;
import lombok.RequiredArgsConstructor;

/**
 * aistio 契约端点（基址 {@code /agentscope}）。
 *
 * <p>覆盖 aistio 契约的全部端点：
 * <ul>
 *   <li>Phase 1：/info、/health、/sessions、/sessions/{id}/state|context|messages、
 *       /subagents、/workspaces</li>
 *   <li>Phase 2：/sessions/{id}/compress、/sessions/{id}/terminate（session-command，受
 *       {@code command-enabled} 闸门保护）</li>
 *   <li>Phase 3：/sessions/{id}/plan-mode、/sessions/{id}/tasks、/sessions/{id}/subagent-tasks、
 *       DELETE /sessions/{id}/subagent-tasks/{taskId}、/teams/join</li>
 * </ul>
 * 仅当 {@code yunxi.aistio.enabled=true} 时装配。</p>
 */
@RestController
@RequestMapping("/agentscope")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yunxi.aistio.enabled", havingValue = "true")
public class AistioContractController {

    private static final Logger log = LoggerFactory.getLogger(AistioContractController.class);

    private final AistioInfoService infoService;
    private final SessionInventoryService sessionInventory;
    private final SubagentTopologyService subagentTopology;
    private final WorkspaceInventoryService workspaceInventory;
    private final GovernanceService governance;
    private final ActiveSessionRegistry registry;

    /** 数据面信息（含能力声明 + MCP 工具名，验收 /verify 用）。 */
    @GetMapping("/info")
    public DataPlaneInfo info() {
        return infoService.buildInfo();
    }

    /** 健康检查。 */
    @GetMapping("/health")
    public HealthResponse health() {
        HealthResponse r = new HealthResponse();
        r.setStatus("ok");
        return r;
    }

    /** 会话清单。 */
    @GetMapping("/sessions")
    public List<SessionSnapshot> listSessions() {
        return sessionInventory.listSessions();
    }

    /** 会话运行状态（含 plan-mode 实时状态）。 */
    @GetMapping("/sessions/{id}/state")
    public SessionState sessionState(@PathVariable("id") String id) {
        return sessionInventory.getState(id);
    }

    /** 会话有效上下文窗口。 */
    @GetMapping("/sessions/{id}/context")
    public ContextSnapshot context(@PathVariable("id") String id) {
        return sessionInventory.getContext(id);
    }

    /** 会话完整历史分页。 */
    @GetMapping("/sessions/{id}/messages")
    public MessagePage messages(@PathVariable("id") String id,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return sessionInventory.getMessages(id, offset, limit);
    }

    /** 子智能体编排树（subagent-inventory）。 */
    @GetMapping("/subagents")
    public List<SubagentInfo> subagents() {
        return subagentTopology.getTopology();
    }

    /** 工作空间清单（workspace-inventory）。 */
    @GetMapping("/workspaces")
    public List<WorkspaceInfo> workspaces() {
        return workspaceInventory.getWorkspaces();
    }

    /** 任务清单（task-query）。 */
    @GetMapping("/sessions/{id}/tasks")
    public List<TaskInfo> tasks(@PathVariable("id") String id) {
        return governance.listTasks(id);
    }

    /** 子任务清单（task-query，与 /tasks 同源，粒度更细）。 */
    @GetMapping("/sessions/{id}/subagent-tasks")
    public List<TaskInfo> subagentTasks(@PathVariable("id") String id) {
        return governance.listTasks(id);
    }

    /** 强制压缩会话上下文（session-command）。 */
    @PostMapping("/sessions/{id}/compress")
    public Map<String, Object> compress(@PathVariable("id") String id) {
        return governance.compress(id);
    }

    /** 终止 / 清理会话（session-command）。 */
    @PostMapping("/sessions/{id}/terminate")
    public Map<String, Object> terminate(@PathVariable("id") String id) {
        return governance.terminate(id);
    }

    /** 切换会话 plan-mode（plan-mode 能力）。 */
    @PostMapping("/sessions/{id}/plan-mode")
    public Map<String, Object> planMode(@PathVariable("id") String id,
            @RequestBody(required = false) PlanModeRequest req) {
        boolean enabled = req != null && req.isEnabled();
        return governance.setPlanMode(id, enabled);
    }

    /** 取消指定子任务（task-query）。 */
    @DeleteMapping("/sessions/{id}/subagent-tasks/{taskId}")
    public Map<String, Object> cancelTask(@PathVariable("id") String id,
            @PathVariable("taskId") String taskId) {
        return governance.cancelTask(id, taskId);
    }

    /** 接纳 aistio 团队加入请求（team-coordination 能力）。 */
    @PostMapping("/teams/join")
    public Map<String, Object> joinTeam(@RequestBody(required = false) Map<String, Object> payload) {
        return governance.joinTeam(payload != null ? payload : Map.of());
    }
}
