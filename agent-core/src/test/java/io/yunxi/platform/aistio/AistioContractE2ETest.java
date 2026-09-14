package io.yunxi.platform.aistio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.Task.State;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.aistio.controller.AistioContractController;
import io.yunxi.platform.aistio.interceptor.AistioAuthInterceptor;
import io.yunxi.platform.aistio.registry.ActiveSessionRegistry;
import io.yunxi.platform.aistio.service.AistioInfoService;
import io.yunxi.platform.aistio.service.GovernanceService;
import io.yunxi.platform.aistio.service.HarnessAgentResolver;
import io.yunxi.platform.aistio.service.SessionInventoryService;
import io.yunxi.platform.aistio.service.SubagentTopologyService;
import io.yunxi.platform.aistio.service.WorkspaceInventoryService;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.agent.mcp.McpConfigStore;
import io.yunxi.platform.conversation.ConversationDomainService;
import io.yunxi.platform.persistence.repository.ConversationRepository;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;

/**
 * aistio 契约端点的端到端测试（无 Spring 上下文切片）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup} 直接装配<b>真实的</b> controller / service /
 * registry / DTO / 鉴权拦截器，仅对 DB / Redis 重依赖（AgentService、Conversation*、
 * AgentDefinitionLoader、McpConfigStore）做 Mock，从而在无 MySQL / Redis 等外部基础设施的情况下
 * 验证整个 HTTP 契约链路与按文档的行为（能力声明、鉴权闸门、404/403、plan-mode、tasks 等）。</p>
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

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        // 模拟开启 aistio（契约级别 3，command 闸门关闭，空内部 token => 鉴权放行）
        properties.setContractLevel(3);
        properties.setCommandEnabled(false);
        properties.setInternalToken("");
        properties.setAgentName("test-agent");

        HarnessAgentResolver agentResolver = new HarnessAgentResolver(agentService);
        AistioInfoService infoService =
                new AistioInfoService(properties, mcpConfigStore, agentDefinitionLoader);
        SessionInventoryService sessionInventory =
                new SessionInventoryService(registry, conversationDomainService, properties, agentResolver);
        SubagentTopologyService subagentTopology = new SubagentTopologyService(agentDefinitionLoader);
        WorkspaceInventoryService workspaceInventory = new WorkspaceInventoryService(registry);
        GovernanceService governance =
                new GovernanceService(registry, properties, agentService, conversationDomainService, agentResolver);
        AistioContractController controller = new AistioContractController(
                infoService, sessionInventory, subagentTopology, workspaceInventory, governance, registry);
        AistioAuthInterceptor interceptor = new AistioAuthInterceptor(properties);

        mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor).build();
    }

    // ---- Phase 1：只读契约 ----

    @Test
    void info_declaresPhase3Capabilities() throws Exception {
        mvc.perform(get("/agentscope/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities").isArray())
                .andExpect(jsonPath("$.capabilities").value(org.hamcrest.Matchers.hasItems(
                        "session-reporting", "context-query", "message-query",
                        "subagent-inventory", "workspace-inventory",
                        "task-query", "plan-mode", "team-coordination")))
                .andExpect(jsonPath("$.name").value("test-agent"))
                .andExpect(jsonPath("$.contract_level").value(3));
    }

    @Test
    void health_returnsOk() throws Exception {
        mvc.perform(get("/agentscope/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void sessions_emptyWhenNoActiveSessions() throws Exception {
        mvc.perform(get("/agentscope/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void workspaces_emptyWhenNoSessions() throws Exception {
        mvc.perform(get("/agentscope/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void subagents_returnsFromDefinitionLoader() throws Exception {
        mvc.perform(get("/agentscope/subagents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ---- 安全 / 错误路径 ----

    @Test
    void state_404WhenSessionUnknown() throws Exception {
        mvc.perform(get("/agentscope/sessions/nonexistent/state"))
                .andExpect(status().isNotFound());
    }

    @Test
    void compress_403WhenCommandDisabled() throws Exception {
        // 会话存在但 command-enabled=false，应在鉴权闸门处返回 403（而非 404）
        registry.register("abc", "user-x", "test-agent", java.time.LocalDateTime.now());
        mvc.perform(post("/agentscope/sessions/abc/compress"))
                .andExpect(status().isForbidden());
    }

    @Test
    void terminate_403WhenCommandDisabled() throws Exception {
        registry.register("abc", "user-x", "test-agent", java.time.LocalDateTime.now());
        mvc.perform(post("/agentscope/sessions/abc/terminate"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tasks_404WhenSessionUnknown() throws Exception {
        mvc.perform(get("/agentscope/sessions/nonexistent/tasks"))
                .andExpect(status().isNotFound());
    }

    @Test
    void planMode_404WhenSessionUnknown() throws Exception {
        mvc.perform(post("/agentscope/sessions/nonexistent/plan-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void teamsJoin_accepts() throws Exception {
        mvc.perform(post("/agentscope/teams/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"t1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    // ---- 会话存在时的真实 agent 链路（plan-mode / tasks）----

    @Test
    void sessionState_reportsPlanModeViaResolver() throws Exception {
        registry.register("sess-1", "user-1", "test-agent", java.time.LocalDateTime.now());
        HarnessAgent harness = mock(HarnessAgent.class);
        when(agentService.getAgentInstance("test-agent")).thenReturn(harness);
        when(harness.isPlanModeActive("user-1", "sess-1")).thenReturn(true);

        mvc.perform(get("/agentscope/sessions/sess-1/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_mode_active").value(true));
    }

    @Test
    void tasks_listsTasksFromAgentState() throws Exception {
        registry.register("sess-2", "user-2", "test-agent", java.time.LocalDateTime.now());

        HarnessAgent harness = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        AgentState state = mock(AgentState.class);
        TaskContextState tcs = mock(TaskContextState.class);
        Task task = mock(Task.class);
        State taskState = mock(State.class);

        when(agentService.getAgentInstance("test-agent")).thenReturn(harness);
        when(harness.getDelegate()).thenReturn(delegate);
        when(delegate.getAgentState(any(RuntimeContext.class))).thenReturn(state);
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

        mvc.perform(get("/agentscope/sessions/sess-2/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("task-1"))
                .andExpect(jsonPath("$[0].state").value("in_progress"))
                .andExpect(jsonPath("$[0].owner").value("expert-a"));
    }
}
