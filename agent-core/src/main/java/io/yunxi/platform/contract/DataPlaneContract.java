package io.yunxi.platform.contract;

import java.util.List;
import java.util.Map;

import io.yunxi.platform.contract.dto.ContextSnapshot;
import io.yunxi.platform.contract.dto.DataPlaneInfo;
import io.yunxi.platform.contract.dto.HealthResponse;
import io.yunxi.platform.contract.dto.MessagePage;
import io.yunxi.platform.contract.dto.SessionSnapshot;
import io.yunxi.platform.contract.dto.SessionState;
import io.yunxi.platform.contract.dto.SubagentInfo;
import io.yunxi.platform.contract.dto.TaskInfo;
import io.yunxi.platform.contract.dto.WorkspaceInfo;

/**
 * aistio 数据面契约（基址 {@code /agentscope}）。
 *
 * <p>这是 yunxi 统一入口网关（{@code agent-ingress}）与 aistio 控制面之间的契约边界。接口与 DTO 下沉到
 * {@code agent-core}，由 {@code agent-aistio} 提供实现（{@code AistioDataPlaneContract}）并委托其域内 service；
 * {@code agent-ingress} 仅依赖本接口，对 {@code agent-aistio} 保持编译隔离（铁律：入口层不依赖 aistio 模块）。</p>
 *
 * <p>覆盖 aistio 契约全部端点：基础契约（/info、/health、/sessions、/subagents、/workspaces）、
 * 会话读操作（state/context/messages/tasks）、会话写操作（compress/terminate/plan-mode）、
 * 以及团队协作（teams/join）。写操作返回 {@code Map<String,Object>}，与 aistio 控制面现有响应结构一致。</p>
 */
public interface DataPlaneContract {

    /** 数据面信息（含能力声明 + MCP 工具名）。 */
    DataPlaneInfo info();

    /** 健康检查。 */
    HealthResponse health();

    /** 会话清单。 */
    List<SessionSnapshot> listSessions();

    /** 会话运行状态（含 plan-mode 实时状态）。 */
    SessionState getState(String id);

    /** 会话有效上下文窗口。 */
    ContextSnapshot getContext(String id);

    /** 会话完整历史分页。 */
    MessagePage getMessages(String id, int offset, int limit);

    /** 子智能体编排树（subagent-inventory）。 */
    List<SubagentInfo> getTopology();

    /** 工作空间清单（workspace-inventory）。 */
    List<WorkspaceInfo> getWorkspaces();

    /** 任务清单（task-query）。 */
    List<TaskInfo> listTasks(String id);

    /** 强制压缩会话上下文（session-command）。 */
    Map<String, Object> compress(String id);

    /** 终止 / 清理会话（session-command）。 */
    Map<String, Object> terminate(String id);

    /** 切换会话 plan-mode（plan-mode 能力）。 */
    Map<String, Object> setPlanMode(String id, boolean enabled);

    /** 取消指定子任务（task-query）。 */
    Map<String, Object> cancelTask(String id, String taskId);

    /** 接纳 aistio 团队加入请求（team-coordination 能力）。 */
    Map<String, Object> joinTeam(Map<String, Object> payload);
}
