package io.yunxi.platform.aistio.contract;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.yunxi.platform.aistio.service.AistioInfoService;
import io.yunxi.platform.aistio.service.GovernanceService;
import io.yunxi.platform.aistio.service.SessionInventoryService;
import io.yunxi.platform.aistio.service.SubagentTopologyService;
import io.yunxi.platform.aistio.service.WorkspaceInventoryService;
import io.yunxi.platform.integration.contract.DataPlaneContract;
import io.yunxi.platform.integration.contract.dto.ContextSnapshot;
import io.yunxi.platform.integration.contract.dto.DataPlaneInfo;
import io.yunxi.platform.integration.contract.dto.HealthResponse;
import io.yunxi.platform.integration.contract.dto.MessagePage;
import io.yunxi.platform.integration.contract.dto.SessionSnapshot;
import io.yunxi.platform.integration.contract.dto.SessionState;
import io.yunxi.platform.integration.contract.dto.SubagentInfo;
import io.yunxi.platform.integration.contract.dto.TaskInfo;
import io.yunxi.platform.integration.contract.dto.WorkspaceInfo;
import lombok.RequiredArgsConstructor;

/**
 * aistio 数据面契约实现（{@code agent-ingress} 的 {@code AistioIngressController} 经本实现消费 {@link DataPlaneContract}）。
 *
 * <p>本类仅作「契约边界适配」：把所有操作委托给 aistio 域内 service（{@code AistioInfoService} /
 * {@code SessionInventoryService} / {@code SubagentTopologyService} / {@code WorkspaceInventoryService} /
 * {@code GovernanceService}），自身不含业务语义。接口与 DTO 下沉在 {@code agent-core}，故
 * {@code agent-ingress} 只依赖 {@code agent-core} 的契约，对 {@code agent-aistio} 保持编译隔离。</p>
 *
 * <p>命令类写操作的 403 闸门（{@code yunxi.aistio.command-enabled=false}）由 {@code GovernanceService}
 * 内部 {@code requireCommandEnabled()} 承载，逻辑留在 aistio 域内，不污染 ingress 的薄传输层。本实现为纯委托。</p>
 *
 * <p>仅当 {@code yunxi.aistio.enabled=true} 时装配；未启用时 {@code agent-ingress} 的
 * {@code AistioIngressController} 经 {@code ObjectProvider} 取到 null，端点优雅降级为 503。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yunxi.aistio.enabled", havingValue = "true")
public class AistioDataPlaneContract implements DataPlaneContract {

    private final AistioInfoService infoService;
    private final SessionInventoryService sessionInventory;
    private final SubagentTopologyService subagentTopology;
    private final WorkspaceInventoryService workspaceInventory;
    private final GovernanceService governance;

    @Override
    public DataPlaneInfo info() {
        return infoService.buildInfo();
    }

    @Override
    public HealthResponse health() {
        HealthResponse r = new HealthResponse();
        r.setStatus("ok");
        return r;
    }

    @Override
    public List<SessionSnapshot> listSessions() {
        return sessionInventory.listSessions();
    }

    @Override
    public SessionState getState(String id) {
        return sessionInventory.getState(id);
    }

    @Override
    public ContextSnapshot getContext(String id) {
        return sessionInventory.getContext(id);
    }

    @Override
    public MessagePage getMessages(String id, int offset, int limit) {
        return sessionInventory.getMessages(id, offset, limit);
    }

    @Override
    public List<SubagentInfo> getTopology() {
        return subagentTopology.getTopology();
    }

    @Override
    public List<WorkspaceInfo> getWorkspaces() {
        return workspaceInventory.getWorkspaces();
    }

    @Override
    public List<TaskInfo> listTasks(String id) {
        return governance.listTasks(id);
    }

    @Override
    public Map<String, Object> compress(String id) {
        return governance.compress(id);
    }

    @Override
    public Map<String, Object> terminate(String id) {
        return governance.terminate(id);
    }

    @Override
    public Map<String, Object> setPlanMode(String id, boolean enabled) {
        return governance.setPlanMode(id, enabled);
    }

    @Override
    public Map<String, Object> cancelTask(String id, String taskId) {
        return governance.cancelTask(id, taskId);
    }

    @Override
    public Map<String, Object> joinTeam(Map<String, Object> payload) {
        return governance.joinTeam(payload != null ? payload : Map.of());
    }
}
