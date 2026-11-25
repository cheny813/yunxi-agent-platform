package io.yunxi.runtime.skilluse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 技能使用事件的 JDBC 存储实现——技能使用统计的单一真相源。
 *
 * <p>建表幂等（CREATE TABLE IF NOT EXISTS），与 MUSE 数据面的 skillStoreDataSource 复用同一存储，
 * 多实例共享同一真相。统计为实时查询（无缓存、无快照），useCount 为全部事件计数，
 * distinctUsers 为去重非空用户数。
 */
public class JdbcSkillUseEventStore implements SkillUseEventStore {

    private final DataSource dataSource;

    public JdbcSkillUseEventStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    private void ensureTable() {
        String ddl = "CREATE TABLE IF NOT EXISTS skill_usage_event ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "skill_name VARCHAR(255) NOT NULL, "
                + "tenant_id VARCHAR(64), "
                + "user_id VARCHAR(128), "
                + "event_type VARCHAR(16) NOT NULL, "
                + "occurred_at TIMESTAMP NOT NULL)";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 skill_usage_event 表失败", e);
        }
    }

    @Override
    public void record(SkillUseEvent event) {
        Instant at = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        String sql = "INSERT INTO skill_usage_event (skill_name, tenant_id, user_id, event_type, occurred_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, event.skill());
            ps.setString(2, event.tenantId());
            ps.setString(3, event.userId());
            ps.setString(4, event.eventType().name());
            ps.setTimestamp(5, Timestamp.from(at));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("记录技能使用事件失败: " + event.skill(), e);
        }
    }

    @Override
    public Map<String, SkillUsageStats> statsFor(Collection<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return Map.of();
        }
        String names = String.join(", ", skills.stream().map(s -> "?").toList());
        String sql = "SELECT skill_name, COUNT(*) AS use_count, COUNT(DISTINCT user_id) AS distinct_users, MAX(occurred_at) AS last_used_at "
                + "FROM skill_usage_event WHERE skill_name IN (" + names + ") GROUP BY skill_name";
        Map<String, SkillUsageStats> result = new HashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String skill : skills) {
                ps.setString(i++, skill);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String skill = rs.getString("skill_name");
                    Timestamp last = rs.getTimestamp("last_used_at");
                    result.put(skill, new SkillUsageStats(
                            skill,
                            rs.getLong("use_count"),
                            rs.getInt("distinct_users"),
                            last != null ? last.toInstant() : null));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("聚合技能使用统计失败", e);
        }
        return result;
    }
}
