package io.yunxi.platform.gateway.core;

import io.yunxi.platform.gateway.GatewayProperties;
import io.yunxi.platform.gateway.channel.MessageContext;
import io.yunxi.platform.gateway.model.GatewaySession;
import io.yunxi.platform.gateway.model.PlatformType;
import io.yunxi.platform.gateway.model.SessionResetPolicy;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关会话管理器
 *
 * <p>维护消息平台 chat_id 与 agent-core conversationId 的映射关系</p>
 */
@Slf4j
public class GatewaySessionManager {

    private final GatewayProperties properties;
    private final Map<String, GatewaySession> sessions = new ConcurrentHashMap<>();

    public GatewaySessionManager(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * 生成会话键
     *
     * <p>DM: {platform}:dm:{chatId}</p>
     * <p>群聊: {platform}:group:{chatId}:{userId}</p>
     */
    public String buildSessionKey(MessageContext context) {
        StringBuilder key = new StringBuilder();
        key.append(context.getPlatform().getCode());
        key.append(":").append(context.getChatType());
        key.append(":").append(context.getChatId());

        if ("group".equals(context.getChatType()) && context.getUserId() != null) {
            key.append(":").append(context.getUserId());
        }

        if (context.getThreadId() != null) {
            key.append(":").append(context.getThreadId());
        }

        return key.toString();
    }

    /**
     * 获取或创建会话
     */
    public GatewaySession getOrCreateSession(MessageContext context) {
        String sessionKey = buildSessionKey(context);

        GatewaySession session = sessions.get(sessionKey);
        if (session != null) {
            // 检查是否需要重置
            if (shouldReset(session)) {
                log.info("[SessionManager] 会话已过期，重置: {}", sessionKey);
                session = resetSession(sessionKey, session);
            } else {
                session.touch();
            }
            return session;
        }

        // 创建新会话
        session = GatewaySession.builder()
                .sessionKey(sessionKey)
                .platform(context.getPlatform())
                .chatId(context.getChatId())
                .chatType(context.getChatType())
                .userId(context.getUserId())
                .userName(context.getUserName())
                .agentName(properties.getDefaultAgent())
                .createdAt(Instant.now())
                .lastActiveAt(Instant.now())
                .messageCount(0)
                .build();

        sessions.put(sessionKey, session);
        log.info("[SessionManager] 创建新会话: key={}, platform={}, chatId={}",
                sessionKey, context.getPlatform(), context.getChatId());
        return session;
    }

    /**
     * 重置会话
     */
    public GatewaySession resetSession(String sessionKey, GatewaySession oldSession) {
        // 保留 platform/chatId/userId 等元信息，清除 conversationId
        GatewaySession newSession = GatewaySession.builder()
                .sessionKey(sessionKey)
                .platform(oldSession.getPlatform())
                .chatId(oldSession.getChatId())
                .chatType(oldSession.getChatType())
                .userId(oldSession.getUserId())
                .userName(oldSession.getUserName())
                .agentName(properties.getDefaultAgent())
                .createdAt(Instant.now())
                .lastActiveAt(Instant.now())
                .messageCount(0)
                .build();

        sessions.put(sessionKey, newSession);
        return newSession;
    }

    /**
     * 获取会话
     */
    public Optional<GatewaySession> getSession(String sessionKey) {
        return Optional.ofNullable(sessions.get(sessionKey));
    }

    /**
     * 更新会话的 conversationId
     */
    public void updateConversationId(String sessionKey, String conversationId) {
        GatewaySession session = sessions.get(sessionKey);
        if (session != null) {
            session.setConversationId(conversationId);
        }
    }

    /**
     * 判断会话是否需要重置
     */
    private boolean shouldReset(GatewaySession session) {
        SessionResetPolicy policy = properties.getSession().getResetPolicy();
        if (policy == SessionResetPolicy.NONE) {
            return false;
        }

        boolean dailyReset = false;
        boolean idleReset = false;

        if (policy == SessionResetPolicy.DAILY || policy == SessionResetPolicy.BOTH) {
            int resetHour = properties.getSession().getDailyResetHour();
            LocalDateTime lastActive = session.getLastActiveAt().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime now = LocalDateTime.now();
            // 如果上次活跃不在今天，且已过了重置时间
            dailyReset = lastActive.toLocalDate().isBefore(now.toLocalDate())
                    && now.toLocalTime().isAfter(LocalTime.of(resetHour, 0));
        }

        if (policy == SessionResetPolicy.IDLE || policy == SessionResetPolicy.BOTH) {
            long idleMinutes = properties.getSession().getIdleTimeoutMinutes();
            long actualIdle = java.time.Duration.between(session.getLastActiveAt(), Instant.now()).toMinutes();
            idleReset = actualIdle > idleMinutes;
        }

        return dailyReset || idleReset;
    }

    /**
     * 获取活跃会话数量
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}
