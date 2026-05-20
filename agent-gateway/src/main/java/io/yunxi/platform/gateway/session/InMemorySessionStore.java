package io.yunxi.platform.gateway.session;

import io.yunxi.platform.gateway.model.GatewaySession;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory session store for development and single-instance deployment
 * Production-grade features: thread-safe with ConcurrentHashMap, auto cleanup
 */
@Slf4j
public class InMemorySessionStore implements SessionStore {

    private final Map<String, GatewaySession> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    @Override
    public boolean save(GatewaySession session) {
        if (closed) {
            log.warn("Session store is closed, cannot save session");
            return false;
        }
        if (session == null || session.getSessionKey() == null) {
            log.warn("Invalid session or sessionKey is null");
            return false;
        }
        sessions.put(session.getSessionKey(), session);
        return true;
    }

    @Override
    public Optional<GatewaySession> findById(String sessionKey) {
        if (closed || sessionKey == null) {
            return Optional.empty();
        }
        GatewaySession session = sessions.get(sessionKey);
        if (session != null && isExpired(session)) {
            sessions.remove(sessionKey);
            return Optional.empty();
        }
        return Optional.ofNullable(session);
    }

    @Override
    public List<GatewaySession> findByUserId(String userId) {
        if (closed || userId == null) {
            return List.of();
        }
        return sessions.values().stream()
                .filter(s -> userId.equals(s.getUserId()))
                .filter(s -> !isExpired(s))
                .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String sessionKey) {
        if (closed || sessionKey == null) {
            return false;
        }
        return sessions.remove(sessionKey) != null;
    }

    @Override
    public int deleteByUserId(String userId) {
        if (closed || userId == null) {
            return 0;
        }
        List<String> toRemove = sessions.values().stream()
                .filter(s -> userId.equals(s.getUserId()))
                .map(GatewaySession::getSessionKey)
                .collect(Collectors.toList());
        toRemove.forEach(sessions::remove);
        return toRemove.size();
    }

    @Override
    public int cleanupExpired() {
        if (closed) {
            return 0;
        }
        List<String> expired = sessions.values().stream()
                .filter(this::isExpired)
                .map(GatewaySession::getSessionKey)
                .collect(Collectors.toList());
        expired.forEach(sessions::remove);
        if (!expired.isEmpty()) {
            log.debug("Cleaned up {} expired sessions", expired.size());
        }
        return expired.size();
    }

    @Override
    public long count() {
        if (closed) {
            return 0;
        }
        cleanupExpired();
        return sessions.size();
    }

    @Override
    public String getStoreType() {
        return "memory";
    }

    @Override
    public boolean isAvailable() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
        sessions.clear();
        log.info("InMemorySessionStore closed");
    }

    private boolean isExpired(GatewaySession session) {
        // Default 24 hours expiration
        if (session == null || session.getLastActiveAt() == null) {
            return true;
        }
        long inactiveTime = System.currentTimeMillis() - session.getLastActiveAt().toEpochMilli();
        return inactiveTime > 24 * 60 * 60 * 1000;
    }
}
