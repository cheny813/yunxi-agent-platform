package io.yunxi.runtime.skilluse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * 技能使用事件 JDBC 存储单测：H2（MySQL 兼容模式）嵌入式验证建表幂等、事件落库与聚合口径。
 */
class JdbcSkillUseEventStoreTest {

    private DataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    @Test
    void createTableIsIdempotent() throws Exception {
        DataSource ds = h2();
        new JdbcSkillUseEventStore(ds);
        // 二次构造复用同一数据源，验证 CREATE TABLE IF NOT EXISTS 幂等
        new JdbcSkillUseEventStore(ds);
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM skill_usage_event")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void recordAndAggregateUseCountAndDistinctUsers() {
        JdbcSkillUseEventStore store = new JdbcSkillUseEventStore(h2());
        Instant t0 = Instant.parse("2026-11-20T10:00:00Z");
        store.record(new SkillUseEvent("weather", "t1", "alice", SkillUseEventType.VIEW, t0));
        store.record(new SkillUseEvent("weather", "t1", "alice", SkillUseEventType.VIEW, t0.plusSeconds(60)));
        store.record(new SkillUseEvent("weather", "t1", "bob", SkillUseEventType.USE, t0.plusSeconds(120)));
        store.record(new SkillUseEvent("sql", "t1", "alice", SkillUseEventType.VIEW, t0.plusSeconds(180)));
        store.record(new SkillUseEvent("sql", "t1", null, SkillUseEventType.VIEW, t0.plusSeconds(240)));

        Map<String, SkillUsageStats> stats = store.statsFor(java.util.List.of("weather", "sql"));

        assertEquals(3, stats.get("weather").useCount());
        assertEquals(2, stats.get("weather").distinctUsers());
        assertEquals(t0.plusSeconds(120), stats.get("weather").lastUsedAt());
        assertEquals(2, stats.get("sql").useCount());
        assertEquals(1, stats.get("sql").distinctUsers());
    }

    @Test
    void unknownSkillIsAbsentFromStats() {
        JdbcSkillUseEventStore store = new JdbcSkillUseEventStore(h2());
        store.record(new SkillUseEvent("weather", null, "u", SkillUseEventType.VIEW, Instant.now()));
        Map<String, SkillUsageStats> stats = store.statsFor(java.util.List.of("weather", "ghost"));
        assertTrue(stats.containsKey("weather"));
        assertFalse(stats.containsKey("ghost"));
    }

    @Test
    void emptySkillListReturnsEmptyStats() {
        JdbcSkillUseEventStore store = new JdbcSkillUseEventStore(h2());
        assertTrue(store.statsFor(java.util.List.of()).isEmpty());
    }

    @Test
    void nullOccurredAtFallsBackToNow() throws Exception {
        DataSource ds = h2();
        JdbcSkillUseEventStore store = new JdbcSkillUseEventStore(ds);
        store.record(new SkillUseEvent("weather", null, "u", SkillUseEventType.VIEW, null));
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT occurred_at FROM skill_usage_event")) {
            assertTrue(rs.next());
            assertNotNull(rs.getTimestamp(1));
        }
    }
}
