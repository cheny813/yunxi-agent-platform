package io.yunxi.platform.aistio.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.Task.State;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.aistio.dto.TaskInfo;
import io.yunxi.platform.aistio.registry.ActiveSessionRegistry;
import io.yunxi.platform.aistio.registry.ActiveSessionRegistry.SessionMeta;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.conversation.ConversationDomainService;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * 治理动作服务（Phase 2 + Phase 3 写 / 管理端点）。
 *
 * <p>覆盖：强制压缩（{@code /compress}）、会话终止（{@code /terminate}）、plan-mode 切换
 * （{@code /plan-mode}）、任务清单读取 / 取消（{@code /tasks}、{@code /subagent-tasks}）、
 * 团队加入（{@code /teams/join}）。</p>
 *
 * <p>所有写操作以 {@link ActiveSessionRegistry} 中的会话元数据为身份来源（userId / agentName），
 * 经 {@link HarnessAgentResolver} 取得运行时 Agent 后操作其 {@code AgentState}。</p>
 */
@Component
@RequiredArgsConstructor
public class GovernanceService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceService.class);

    private final ActiveSessionRegistry registry;
    private final AistioIntegrationProperties properties;
    private final AgentService agentService;
    private final ConversationDomainService conversationDomainService;
    private final HarnessAgentResolver agentResolver;

    /**
     * 强制压缩指定会话上下文（session-command 能力，需 commandEnabled）。
     *
     * @param sessionId 会话 ID
     * @return 压缩结果
     */
    public Map<String, Object> compress(String sessionId) {
        SessionMeta meta = requireSession(sessionId);
        requireCommandEnabled();
        HarnessAgent agent = agentResolver.resolve(meta.agentName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "agent not found: " + meta.agentName()));
        if (agent.getModel() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "agent has no model configured, cannot compress");
        }
        RuntimeContext rc = RuntimeContext.builder().userId(meta.userId()).sessionId(sessionId).build();
        var delegate = agent.getDelegate();
        AgentState state = delegate.getAgentState(rc);
        if (state == null) {
            return Map.of("sessionId", sessionId, "compressed", false, "reason", "no agent state");
        }
        List<Msg> allMsgs = state.contextMutable();
        if (allMsgs.isEmpty()) {
            return Map.of("sessionId", sessionId, "compressed", false, "reason", "empty context");
        }
        try {
            // 强制压缩：将触发阈值压到 1 条，绕过 GA 默认的阈值触发，复用框架原生 compactor。
            CompactionConfig forceConfig = CompactionConfig.builder().triggerMessages(1).build();
            MemoryFlushManager flushManager =
                    new MemoryFlushManager(agent.getWorkspaceManager(), agent.getModel());
            ConversationCompactor compactor = new ConversationCompactor(agent.getModel(), flushManager);
            Optional<List<Msg>> result =
                    compactor.compactIfNeeded(rc, allMsgs, forceConfig, agent.getName(), sessionId).block();
            if (result != null && result.isPresent()) {
                List<Msg> compacted = result.get();
                allMsgs.clear();
                allMsgs.addAll(compacted);
                agent.getStateStore().save(meta.userId(), sessionId, "agent_state", state);
                return Map.of("sessionId", sessionId, "compressed", true, "messagesAfter", compacted.size());
            }
            return Map.of("sessionId", sessionId, "compressed", false, "reason", "compactor returned nothing");
        } catch (Exception e) {
            log.warn("强制压缩失败 session={}: {}", sessionId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "compress failed: " + e.getMessage());
        }
    }

    /**
     * 终止 / 清理指定会话（session-command 能力，需 commandEnabled）。
     *
     * @param sessionId 会话 ID
     * @return 终止结果
     */
    public Map<String, Object> terminate(String sessionId) {
        SessionMeta meta = requireSession(sessionId);
        requireCommandEnabled();
        agentResolver.resolve(meta.agentName()).ifPresent(agent -> {
            AgentStateStore store = agent.getStateStore();
            if (store != null) {
                try {
                    store.delete(meta.userId(), sessionId);
                } catch (Exception e) {
                    log.warn("释放 AgentState 槽失败 session={}: {}", sessionId, e.getMessage());
                }
            }
        });
        try {
            conversationDomainService.deleteConversation(sessionId);
        } catch (Exception e) {
            log.warn("删除会话实体失败 session={}: {}", sessionId, e.getMessage());
        }
        registry.remove(sessionId);
        return Map.of("sessionId", sessionId, "terminated", true);
    }

    /**
     * 切换指定会话的 plan-mode（plan-mode 能力）。
     *
     * @param sessionId 会话 ID
     * @param enabled   是否开启
     * @return 切换结果
     */
    public Map<String, Object> setPlanMode(String sessionId, boolean enabled) {
        SessionMeta meta = requireSession(sessionId);
        HarnessAgent agent = agentResolver.resolve(meta.agentName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "agent not found: " + meta.agentName()));
        if (enabled) {
            agent.enterPlanMode(meta.userId(), sessionId);
        } else {
            agent.exitPlanMode(meta.userId(), sessionId);
        }
        return Map.of("sessionId", sessionId, "planMode", enabled);
    }

    /**
     * 读取指定会话的任务清单（task-query 能力）。
     *
     * @param sessionId 会话 ID
     * @return 任务信息列表
     */
    public List<TaskInfo> listTasks(String sessionId) {
        SessionMeta meta = requireSession(sessionId);
        HarnessAgent agent = agentResolver.resolve(meta.agentName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "agent not found: " + meta.agentName()));
        RuntimeContext rc = RuntimeContext.builder().userId(meta.userId()).sessionId(sessionId).build();
        AgentState state = agent.getDelegate().getAgentState(rc);
        if (state == null || state.getTasksContext() == null) {
            return List.of();
        }
        List<Task> tasks = state.getTasksContext().getTasks();
        return tasks.stream().map(this::toTaskInfo).toList();
    }

    /**
     * 取消指定会话中的一个子任务（task-query 能力）。
     *
     * @param sessionId 会话 ID
     * @param taskId    任务 ID
     * @return 取消结果
     */
    public Map<String, Object> cancelTask(String sessionId, String taskId) {
        SessionMeta meta = requireSession(sessionId);
        HarnessAgent agent = agentResolver.resolve(meta.agentName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "agent not found: " + meta.agentName()));
        RuntimeContext rc = RuntimeContext.builder().userId(meta.userId()).sessionId(sessionId).build();
        AgentState state = agent.getDelegate().getAgentState(rc);
        if (state != null && state.getTasksContext() != null) {
            state.getTasksContext().tasksMutable().removeIf(t -> taskId.equals(t.getId()));
            agent.getStateStore().save(meta.userId(), sessionId, "agent_state", state);
        }
        return Map.of("sessionId", sessionId, "taskId", taskId, "cancelled", true);
    }

    /**
     * 接纳 aistio 控制面下发的团队加入请求（team-coordination 能力）。
     *
     * <p>真正的团队任务经由 aistio gRPC 下行推送（实时事件上报通道）；HTTP 端点仅做接纳确认。</p>
     *
     * @param payload 团队加入请求体
     * @return 接纳结果
     */
    public Map<String, Object> joinTeam(Map<String, Object> payload) {
        log.info("收到 aistio 团队加入请求: {}", payload);
        return Map.of("accepted", true, "note", "team tasks are delivered via the aistio gRPC channel");
    }

    private SessionMeta requireSession(String sessionId) {
        SessionMeta meta = registry.get(sessionId);
        if (meta == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + sessionId);
        }
        return meta;
    }

    private void requireCommandEnabled() {
        if (!properties.isCommandEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "session-command capability is disabled (yunxi.aistio.command-enabled=false)");
        }
    }

    private TaskInfo toTaskInfo(Task t) {
        TaskInfo info = new TaskInfo();
        info.setId(t.getId());
        info.setSubject(t.getSubject());
        info.setDescription(t.getDescription());
        State st = t.getState();
        info.setState(st != null ? st.getWire() : null);
        info.setOwner(t.getOwner());
        info.setCreatedAt(t.getCreatedAt());
        info.setBlockedBy(t.getBlockedBy());
        return info;
    }
}
