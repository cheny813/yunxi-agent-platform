package io.yunxi.platform.gateway.session;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.yunxi.platform.gateway.model.GatewaySession;
import io.yunxi.platform.gateway.model.PlatformType;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SQLite session persistent store - production-grade implementation
 * Features: connection pool, auto schema creation, scheduled cleanup, error isolation, graceful degradation
 */
@Slf4j
public class SqliteSessionStore implements SessionStore {

    private static final String CREATE_TABLE_SQL =
        "CREATE TABLE IF NOT EXISTS gateway_sessions (" +
        "  session_key TEXT PRIMARY KEY," +
        "  platform TEXT NOT NULL," +
        "  chat_id TEXT NOT NULL," +
        "  chat_type TEXT NOT NULL," +
        "  user_id TEXT," +
        "  user_name TEXT," +
        "  conversation_id TEXT," +
        "  agent_name TEXT," +
        "  model_override TEXT," +
        "  created_at INTEGER NOT NULL," +
        "  last_active_at INTEGER NOT NULL," +
        "  message_count INTEGER DEFAULT 0," +
        "  extra TEXT" +
        ")";

    private static final String CREATE_INDEX_CHAT_ID =
        "CREATE INDEX IF NOT EXISTS idx_sessions_chat_id ON gateway_sessions(chat_id)";

    private static final String CREATE_INDEX_USER_ID =
        "CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON gateway_sessions(user_id)";

    private static final String CREATE_INDEX_CONVERSATION =
        "CREATE INDEX IF NOT EXISTS idx_sessions_conversation ON gateway_sessions(conversation_id)";

    private static final String INSERT_OR_REPLACE_SQL =
        "INSERT OR REPLACE INTO gateway_sessions " +
        "(session_key, platform, chat_id, chat_type, user_id, user_name, " +
        " conversation_id, agent_name, model_override, created_at, last_active_at, message_count, extra) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_KEY_SQL =
        "SELECT * FROM gateway_sessions WHERE session_key = ?";

    private static final String SELECT_BY_USER_ID_SQL =
        "SELECT * FROM gateway_sessions WHERE user_id = ? ORDER BY last_active_at DESC";

    private static final String SELECT_BY_CHAT_ID_SQL =
        "SELECT * FROM gateway_sessions WHERE chat_id = ? ORDER BY last_active_at DESC";

    private static final String DELETE_BY_KEY_SQL =
        "DELETE FROM gateway_sessions WHERE session_key = ?";

    private static final String DELETE_BY_USER_ID_SQL =
        "DELETE FROM gateway_sessions WHERE user_id = ?";

    private static final String DELETE_INACTIVE_SQL =
        "DELETE FROM gateway_sessions WHERE last_active_at < ?";

    private static final String COUNT_SQL =
        "SELECT COUNT(*) FROM gateway_sessions";

    private final HikariDataSource dataSource;
    private final ScheduledExecutorService cleanupExecutor;
    private final long sessionTimeoutMillis;
    private volatile boolean available = false;
    private volatile boolean closed = false;

    public SqliteSessionStore(String dbPath) {
        this(dbPath, 300, 86400);
    }

    public SqliteSessionStore(String dbPath, int cleanupIntervalSeconds, int defaultTtlSeconds) {
        this.sessionTimeoutMillis = defaultTtlSeconds * 1000L;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(300000);

        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", "5000");

        this.dataSource = new HikariDataSource(config);

        try {
            initializeSchema();
            this.available = true;
            log.info("SQLite session store initialized: {}", dbPath);
        } catch (Exception e) {
            log.error("Failed to initialize SQLite session store", e);
            this.available = false;
            this.dataSource.close();
            throw new RuntimeException("Failed to initialize SQLite session store", e);
        }

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpired,
            cleanupIntervalSeconds,
            cleanupIntervalSeconds,
            TimeUnit.SECONDS
        );
    }

    private void initializeSchema() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            stmt.execute(CREATE_INDEX_CHAT_ID);
            stmt.execute(CREATE_INDEX_USER_ID);
            stmt.execute(CREATE_INDEX_CONVERSATION);
        }
    }

    @Override
    public boolean save(GatewaySession session) {
        if (!checkAvailable()) {
            return false;
        }
        if (session == null || session.getSessionKey() == null) {
            log.warn("Invalid session or sessionKey is null");
            return false;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_OR_REPLACE_SQL)) {
            ps.setString(1, session.getSessionKey());
            ps.setString(2, session.getPlatform() != null ? session.getPlatform().name() : null);
            ps.setString(3, session.getChatId());
            ps.setString(4, session.getChatType());
            ps.setString(5, session.getUserId());
            ps.setString(6, session.getUserName());
            ps.setString(7, session.getConversationId());
            ps.setString(8, session.getAgentName());
            ps.setString(9, session.getModelOverride());
            ps.setLong(10, session.getCreatedAt() != null ? session.getCreatedAt().toEpochMilli() : 0);
            ps.setLong(11, session.getLastActiveAt() != null ? session.getLastActiveAt().toEpochMilli() : 0);
            ps.setLong(12, session.getMessageCount());
            ps.setString(13, serializeExtra(session.getExtra()));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to save session: {}", session.getSessionKey(), e);
            return false;
        }
    }

    @Override
    public Optional<GatewaySession> findById(String sessionKey) {
        if (!checkAvailable() || sessionKey == null) {
            return Optional.empty();
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_KEY_SQL)) {
            ps.setString(1, sessionKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    GatewaySession session = mapResultSetToSession(rs);
                    if (!isExpired(session)) {
                        return Optional.of(session);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find session by key: {}", sessionKey, e);
        }
        return Optional.empty();
    }

    @Override
    public List<GatewaySession> findByUserId(String userId) {
        if (!checkAvailable() || userId == null) {
            return List.of();
        }

        List<GatewaySession> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER_ID_SQL)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GatewaySession session = mapResultSetToSession(rs);
                    if (!isExpired(session)) {
                        result.add(session);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find sessions by user id: {}", userId, e);
        }
        return result;
    }

    public List<GatewaySession> findByChatId(String chatId) {
        if (!checkAvailable() || chatId == null) {
            return List.of();
        }

        List<GatewaySession> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_CHAT_ID_SQL)) {
            ps.setString(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GatewaySession session = mapResultSetToSession(rs);
                    if (!isExpired(session)) {
                        result.add(session);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find sessions by chat id: {}", chatId, e);
        }
        return result;
    }

    @Override
    public boolean delete(String sessionKey) {
        if (!checkAvailable() || sessionKey == null) {
            return false;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_KEY_SQL)) {
            ps.setString(1, sessionKey);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete session: {}", sessionKey, e);
            return false;
        }
    }

    @Override
    public int deleteByUserId(String userId) {
        if (!checkAvailable() || userId == null) {
            return 0;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_USER_ID_SQL)) {
            ps.setString(1, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete sessions by user id: {}", userId, e);
            return 0;
        }
    }

    @Override
    public int cleanupExpired() {
        if (!checkAvailable()) {
            return 0;
        }

        long cutoffTime = System.currentTimeMillis() - sessionTimeoutMillis;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_INACTIVE_SQL)) {
            ps.setLong(1, cutoffTime);
            int count = ps.executeUpdate();
            if (count > 0) {
                log.debug("Cleaned up {} expired sessions", count);
            }
            return count;
        } catch (SQLException e) {
            log.error("Failed to cleanup expired sessions", e);
            return 0;
        }
    }

    @Override
    public long count() {
        if (!checkAvailable()) {
            return 0;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_SQL);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("Failed to count sessions", e);
        }
        return 0;
    }

    @Override
    public String getStoreType() {
        return "sqlite";
    }

    @Override
    public boolean isAvailable() {
        return available && !closed;
    }

    private boolean checkAvailable() {
        if (closed) {
            log.warn("Session store is closed");
            return false;
        }
        if (!available) {
            log.warn("Session store is not available");
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        closed = true;
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        log.info("SQLite session store closed");
    }

    private boolean isExpired(GatewaySession session) {
        if (session == null || session.getLastActiveAt() == null) {
            return true;
        }
        long inactiveTime = System.currentTimeMillis() - session.getLastActiveAt().toEpochMilli();
        return inactiveTime > sessionTimeoutMillis;
    }

    private GatewaySession mapResultSetToSession(ResultSet rs) throws SQLException {
        GatewaySession session = new GatewaySession();
        session.setSessionKey(rs.getString("session_key"));
        String platformStr = rs.getString("platform");
        if (platformStr != null) {
            try {
                session.setPlatform(PlatformType.valueOf(platformStr));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown platform type: {}", platformStr);
            }
        }
        session.setChatId(rs.getString("chat_id"));
        session.setChatType(rs.getString("chat_type"));
        session.setUserId(rs.getString("user_id"));
        session.setUserName(rs.getString("user_name"));
        session.setConversationId(rs.getString("conversation_id"));
        session.setAgentName(rs.getString("agent_name"));
        session.setModelOverride(rs.getString("model_override"));

        long createdAt = rs.getLong("created_at");
        if (createdAt > 0) {
            session.setCreatedAt(Instant.ofEpochMilli(createdAt));
        }
        long lastActiveAt = rs.getLong("last_active_at");
        if (lastActiveAt > 0) {
            session.setLastActiveAt(Instant.ofEpochMilli(lastActiveAt));
        }
        session.setMessageCount(rs.getLong("message_count"));
        session.setExtra(deserializeExtra(rs.getString("extra")));
        return session;
    }

    private String serializeExtra(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : extra.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":\"");
            sb.append(entry.getValue() != null ? entry.getValue().toString() : "").append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private Map<String, Object> deserializeExtra(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        try {
            json = json.trim();
            if (json.startsWith("{") && json.endsWith("}")) {
                json = json.substring(1, json.length() - 1);
                String[] pairs = json.split(",");
                for (String pair : pairs) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim().replace("\"", "");
                        String value = kv[1].trim().replace("\"", "");
                        result.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize extra: {}", json);
        }
        return result.isEmpty() ? null : result;
    }

    DataSource getDataSource() {
        return dataSource;
    }
}
