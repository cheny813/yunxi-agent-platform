package io.yunxi.platform.aistio;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.Task.State;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.aistio.contract.AistioDataPlaneContract;
import io.yunxi.platform.aistio.service.AistioInfoService;
import io.yunxi.platform.aistio.service.ContextSnapshotCompactor;
import io.yunxi.platform.aistio.service.GovernanceService;
import io.yunxi.platform.aistio.service.HarnessAgentResolver;
import io.yunxi.platform.aistio.service.SessionInventoryService;
import io.yunxi.platform.aistio.service.SubagentTopologyService;
import io.yunxi.platform.aistio.service.WorkspaceInventoryService;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.contract.DataPlaneContract;
import io.yunxi.platform.contract.dto.DataPlaneInfo;
import io.yunxi.platform.contract.dto.HealthResponse;
import io.yunxi.platform.contract.dto.SessionState;
import io.yunxi.platform.contract.dto.TaskInfo;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.agent.mcp.McpConfigStore;
import io.yunxi.platform.conversation.ConversationDomainService;
import io.yunxi.platform.persistence.repository.ConversationRepository;
import io.yunxi.platform.registry.ActiveSessionRegistry;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.OrchestrationConfig;

/**
 * aistio 数据面契约实现（{@code AistioDataPlaneContract}）的契约测试（无 Spring 上下文切片）。
 *
 * <p>直接装配<b>真实的</b>契约实现 + service / registry，仅对 DB / Redis 重依赖
 * （AgentService、Conversation*、AgentDefinitionLoader、McpConfigStore）做 Mock，从而在无 MySQL / Redis
 * 等外部基础设施的情况下验证整个契约链路与按文档的行为（能力声明、命令闸门 403、未知会话 404、plan-mode、tasks 等）。</p>
 *
 * <p>HTTP 边缘由 {@code agent-ingress} 的 {@code AistioIngressController} 承载（仅依赖本契约接口），
 * 其路由与「aistio 未启用时 503」行为在 ingress 模块内单独测试。</p>
 */
@ExtendWith(MockitoExtension.class)
class AistioContractE2ETest {

    private final ActiveSessionRegistry registry = new ActiveSessionRegistry();
    private final AistioIntegrationProperties properties = new AistioIntegrationProperties();

    @Mock
    private AgentService agentService;
    @Mock
    private ConversationDomainService conversationDomainService;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private AgentDefinitionLoader agentDefinitionLoader;
    @Mock
    private McpConfigStore mcpConfigStore;
    @Mock
    private ModelFactory modelFactory;

    private DataPlaneContract contract;

    @BeforeEach
    void setup() {
        // 模拟开启 aistio（契约级别 3，command 闸门关闭）
        properties.setContractLevel(3);
        properties.setCommandEnabled(false);
        properties.setInternalToken("");
        properties.setAgentName("test-agent");

        HarnessAgentResolver agentResolver = new HarnessAgentResolver(agentService);
        AistioInfoService infoService =
                new AistioInfoService(properties, mcpConfigStore, agentDefinitionLoader);
        ContextSnapshotCompactor contextCompactor = new ContextSnapshotCompactor(modelFactory, properties);
        SessionInventoryService sessionInventory = new SessionInventoryService(
                registry, conversationDomainService, properties, agentResolver, contextCompactor);
        SubagentTopologyService subagentTopology = new SubagentTopologyService(agentDefinitionLoader);
        WorkspaceInventoryService workspaceInventory = new WorkspaceInventoryService(registry);
        GovernanceService governance =
                new GovernanceService(registry, properties, agentService, conversationDomainService, agentResolver);
        contract = new AistioDataPlaneContract(
                infoService, sessionInventory, subagentTopology, workspaceInventory, governance);
    }

    // ---- 只读契约 ----

    @Test
    void info_declaresLevel3Capabilities() {
        DataPlaneInfo info = contract.info();
        assertTrue(info.getCapabilities().contains("session-reporting"));
        assertTrue(info.getCapabilities().contains("context-query"));
        assertTrue(info.getCapabilities().contains("message-query"));
        assertTrue(info.getCapabilities().contains("subagent-inventory"));
        assertTrue(info.getCapabilities().contains("workspace-inventory"));
        assertTrue(info.getCapabilities().contains("task-query"));
        assertTrue(info.getCapabilities().contains("plan-mode"));
        assertTrue(info.getCapabilities().contains("team-coordination"));
        assertEquals("test-agent", info.getName());
        assertEquals(3, info.getContractLevel());
    }

    @Test
    void health_returnsOk() {
        HealthResponse health = contract.health();
        assertEquals("ok", health.getStatus());
    }

    @Test
    void sessions_emptyWhenNoActiveSessions() {
        assertTrue(contract.listSessions().isEmpty());
    }

    @Test
    void workspaces_emptyWhenNoSessions() {
        assertTrue(contract.getWorkspaces().isEmpty());
    }

    @Test
    void subagents_returnsFromDefinitionLoader() {
        AgentDefinition def = mock(AgentDefinition.class);
        OrchestrationConfig orch = mock(OrchestrationConfig.class);
        when(def.getName()).thenReturn("test-agent");
        when(def.getDescription()).thenReturn("desc");
        when(def.getOrchestration()).thenReturn(orch);
        when(orch.isDeclarationOnly()).thenReturn(false);
        when(orch.getPattern()).thenReturn("single");
        when(orch.getExperts()).thenReturn(List.of());
        when(agentDefinitionLoader.getAgentDefinitions()).thenReturn(List.of(def));

        assertFalse(contract.getTopology().isEmpty());
    }

    // ---- 安全 / 错误路径 ----

    @Test
    void state_404WhenSessionUnknown() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> contract.getState("nonexistent"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void compress_403WhenCommandDisabled() {
        registry.register("abc", "user-x", "test-agent", java.time.LocalDateTime.now());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> contract.compress("abc"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void terminate_403WhenCommandDisabled() {
        registry.register("abc", "user-x", "test-agent", java.time.LocalDateTime.now());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> contract.terminate("abc"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void tasks_404WhenSessionUnknown() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> contract.listTasks("nonexistent"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void planMode_404WhenSessionUnknown() {
        // plan-mode 不受 command 闸门限制，未知会话由 requireSession 返回 404
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> contract.setPlanMode("nonexistent", true));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void teamsJoin_accepts() {
        Map<String, Object> r = contract.joinTeam(Map.of("teamId", "t1"));
        assertEquals(true, r.get("accepted"));
    }

    // ---- 会话存在时的真实 agent 链路（plan-mode / tasks）----

    @Test
    void sessionState_reportsPlanModeViaResolver() {
        registry.register("sess-1", "user-1", "test-agent", java.time.LocalDateTime.now());
        HarnessAgent harness = mock(HarnessAgent.class);
        when(agentService.getAgentInstance("test-agent")).thenReturn(harness);
        when(harness.isPlanModeActive("user-1", "sess-1")).thenReturn(true);

        SessionState state = contract.getState("sess-1");
        assertEquals(true, state.isPlanModeActive());
    }

    @Test
    void tasks_listsTasksFromAgentState() {
        registry.register("sess-2", "user-2", "test-agent", java.time.LocalDateTime.now());

        HarnessAgent harness = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        AgentState state = mock(AgentState.class);
        TaskContextState tcs = mock(TaskContextState.class);
        Task task = mock(Task.class);
        State taskState = mock(State.class);

        when(agentService.getAgentInstance("test-agent")).thenReturn(harness);
        when(harness.getDelegate()).thenReturn(delegate);
        when(delegate.getAgentState(org.mockito.ArgumentMatchers.any(RuntimeContext.class))).thenReturn(state);
        when(state.getTasksContext()).thenReturn(tcs);
        when(tcs.getTasks()).thenReturn(List.of(task));
        when(task.getId()).thenReturn("task-1");
        when(task.getSubject()).thenReturn("调研竞品");
        when(task.getDescription()).thenReturn("对比主流方案");
        when(task.getState()).thenReturn(taskState);
        when(taskState.getWire()).thenReturn("in_progress");
        when(task.getOwner()).thenReturn("expert-a");
        when(task.getCreatedAt()).thenReturn("2026-09-10T10:00:00Z");
        when(task.getBlockedBy()).thenReturn(List.of());

        List<TaskInfo> tasks = contract.listTasks("sess-2");
        assertEquals(1, tasks.size());
        assertEquals("task-1", tasks.get(0).getId());
        assertEquals("in_progress", tasks.get(0).getState());
        assertEquals("expert-a", tasks.get(0).getOwner());
    }
}
