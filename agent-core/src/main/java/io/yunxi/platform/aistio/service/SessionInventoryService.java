package io.yunxi.platform.aistio.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.aistio.dto.ContextSnapshot;
import io.yunxi.platform.aistio.dto.MessagePage;
import io.yunxi.platform.aistio.dto.SessionSnapshot;
import io.yunxi.platform.aistio.dto.SessionState;
import io.yunxi.platform.aistio.registry.ActiveSessionRegistry;
import io.yunxi.platform.aistio.registry.ActiveSessionRegistry.SessionMeta;
import io.yunxi.platform.conversation.ConversationDomainService;
import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.RequiredArgsConstructor;

/**
 * 会话清单 / 状态 / 上下文 / 历史消息的读取服务。
 *
 * <p>数据源：{@link ActiveSessionRegistry}（会话清单）+ {@link ConversationDomainService}
 * （会话实体、消息）。其中状态推导基于会话最后活跃时间与末条消息角色，区别于 AgentScope-Java PhaseTracker
 * 的「按 agentName 取最近一次执行」语义。</p>
 */
@Component
@RequiredArgsConstructor
public class SessionInventoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionInventoryService.class);

    private final ActiveSessionRegistry registry;
    private final ConversationDomainService conversationDomainService;
    private final AistioIntegrationProperties properties;
    private final HarnessAgentResolver agentResolver;

    /**
     * 列出索引中的全部会话快照。
     *
     * @return 会话快照列表
     */
    public List<SessionSnapshot> listSessions() {
        List<SessionSnapshot> out = new ArrayList<>();
        for (SessionMeta meta : registry.list()) {
            SessionSnapshot s = new SessionSnapshot();
            s.setId(meta.sessionId());
            s.setAgentName(meta.agentName());
            s.setUserId(meta.userId());
            s.setCreatedAt(toIso(meta.createdAt()));
            try {
                ConversationEntity entity = conversationDomainService.getConversation(meta.sessionId());
                s.setUpdatedAt(toIso(entity.getLastUpdatedAt()));
                s.setStatus(deriveStatus(entity));
            } catch (Exception e) {
                s.setUpdatedAt(toIso(meta.createdAt()));
                s.setStatus("idle");
            }
            out.add(s);
        }
        return out;
    }

    /**
     * 获取指定会话的运行状态。
     *
     * @param id 会话 ID
     * @return 会话状态
     */
    public SessionState getState(String id) {
        SessionMeta meta = registry.get(id);
        ConversationEntity entity = conversationDomainService.getConversation(id);
        // 会话既不在活跃索引、也无会话实体时视为不存在
        if (meta == null && entity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id);
        }
        SessionState state = new SessionState();
        String agentName = meta != null ? meta.agentName()
                : (entity != null ? entity.getAgentName() : null);
        state.setAgent(agentName);
        state.setMessageCount(entity != null && entity.getMessages() != null
                ? entity.getMessages().size() : 0);
        state.setLastActivity(entity != null ? toIso(entity.getLastUpdatedAt())
                : (meta != null ? toIso(meta.createdAt()) : null));
        state.setStatus(entity != null ? deriveStatus(entity) : "idle");

        // plan-mode 实时状态（Phase 3 plan-mode 能力）
        boolean planActive = false;
        if (meta != null && meta.agentName() != null) {
            planActive = agentResolver.resolve(meta.agentName())
                    .map(h -> h.isPlanModeActive(meta.userId(), id))
                    .orElse(false);
        }
        state.setPlanModeActive(planActive);

        return state;
    }

    /**
     * 获取指定会话的有效上下文窗口（最近 N 轮，见 properties.contextWindow）。
     *
     * @param id 会话 ID
     * @return 上下文快照
     */
    public ContextSnapshot getContext(String id) {
        List<Object> messages = conversationDomainService.getConversationMessages(id);
        int size = messages.size();
        int n = Math.min(properties.getContextWindow(), size);
        StringBuilder sb = new StringBuilder();
        for (int i = size - n; i < size; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) messages.get(i);
            sb.append(m.getOrDefault("role", "unknown")).append(": ")
                    .append(m.getOrDefault("content", "")).append("\n");
        }
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setContext(sb.toString());
        snapshot.setTokens(sb.length() / 4); // 粗略估算
        return snapshot;
    }

    /**
     * 分页获取会话完整历史。
     *
     * @param id     会话 ID
     * @param offset 偏移量
     * @param limit  页大小（<=0 表示取全部）
     * @return 消息分页
     */
    public MessagePage getMessages(String id, int offset, int limit) {
        List<Object> all = conversationDomainService.getConversationMessages(id);
        int total = all.size();
        int from = Math.max(0, offset);
        int to = Math.min(total, from + (limit <= 0 ? total : limit));
        List<Object> page = from < to ? new ArrayList<>(all.subList(from, to)) : new ArrayList<>();
        MessagePage result = new MessagePage();
        result.setMessages(page);
        result.setTotal(total);
        result.setHasMore(to < total);
        return result;
    }

    /**
     * 基于会话实体推导状态：末条消息为用户 → running（处理中），否则 idle。
     *
     * @param entity 会话实体
     * @return runnable / running / idle
     */
    private String deriveStatus(ConversationEntity entity) {
        List<Msg> messages = entity.getMessages();
        if (messages == null || messages.isEmpty()) {
            return "idle";
        }
        try {
            Msg last = messages.get(messages.size() - 1);
            MsgRole role = last.getRole();
            if (role == MsgRole.USER) {
                return "running";
            }
            return "idle";
        } catch (Exception e) {
            log.debug("推导会话状态失败，回退 idle: {}", e.getMessage());
            return "idle";
        }
    }

    private String toIso(LocalDateTime time) {
        return time != null ? time.atZone(ZoneId.systemDefault()).toInstant().toString() : null;
    }
}
