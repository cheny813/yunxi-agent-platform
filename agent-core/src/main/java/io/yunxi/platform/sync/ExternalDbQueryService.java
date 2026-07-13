package io.yunxi.platform.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部数据库查询服务
 * <p>
 * 通过 JDBC 直接连接目标数据库执行查询，避免远程代理开销。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * externalDbQueryService.query(
 *     "jdbc:mysql://192.168.10.153:3306/nutrition_db",  // JDBC URL
 *     "root", "password",                                 // 凭证
 *     "SELECT * FROM dishes WHERE id > 100",              // SQL
 *     1000                                                // limit
 * );
 * }</pre>
 *
 * @version 2.0.0
 * @since 2.0.0
 */
@Slf4j
@Service
public class ExternalDbQueryService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 缓存 DataSource 避免重复创建连接池（按 JDBC URL 缓存） */
    private final Map<String, HikariDataSource> dataSourceCache = new ConcurrentHashMap<>();

    /**
     * 执行 SQL 查询并返回 JSON 字符串
     *
     * @param jdbcUrl       JDBC URL（如 jdbc:mysql://host:port/database）
     * @param username      数据库用户名
     * @param password      数据库密码
     * @param sql           SQL 查询语句
     * @param limit          结果数量限制
     * @return JSON 数组字符串
     */
    @SuppressWarnings("unchecked")
    public String query(String jdbcUrl, String username, String password, String sql, int limit) {
        // 添加 LIMIT
        String limitedSql = addLimit(sql, limit);

        JdbcTemplate jdbc = getJdbcTemplate(jdbcUrl, username, password);

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(limitedSql);
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            log.error("数据库查询失败: jdbcUrl={}, sql={}", jdbcUrl, sql, e);
            return "[]";
        }
    }

    /**
     * 执行 SQL 查询（默认 limit=10000）
     */
    public String query(String jdbcUrl, String username, String password, String sql) {
        return query(jdbcUrl, username, password, sql, 10000);
    }

    /**
     * 获取表结构
     */
    @SuppressWarnings("unchecked")
    public String describeTable(String jdbcUrl, String username, String password, String tableName) {
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, " +
                "IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, COLUMN_KEY " +
                "FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_NAME = '" + tableName + "' ORDER BY ORDINAL_POSITION";

        JdbcTemplate jdbc = getJdbcTemplate(jdbcUrl, username, password);

        try {
            List<Map<String, Object>> columns = jdbc.queryForList(sql);
            return objectMapper.writeValueAsString(columns);
        } catch (Exception e) {
            log.error("获取表结构失败: table={}", tableName, e);
            return "[]";
        }
    }

    /**
     * 列出所有表
     */
    @SuppressWarnings("unchecked")
    public String listTables(String jdbcUrl, String username, String password) {
        JdbcTemplate jdbc = getJdbcTemplate(jdbcUrl, username, password);

        try {
            List<Map<String, Object>> tables = jdbc.queryForList("SHOW TABLES");
            return objectMapper.writeValueAsString(tables);
        } catch (Exception e) {
            log.error("列出表失败", e);
            return "[]";
        }
    }

    /**
     * 获取或创建 JdbcTemplate（按 JDBC URL 缓存 DataSource）
     */
    private JdbcTemplate getJdbcTemplate(String jdbcUrl, String username, String password) {
        HikariDataSource ds = dataSourceCache.get(jdbcUrl);
        if (ds == null || ds.isClosed()) {
            synchronized (dataSourceCache) {
                ds = dataSourceCache.get(jdbcUrl);
                if (ds == null || ds.isClosed()) {
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(jdbcUrl);
                    config.setUsername(username);
                    config.setPassword(password);
                    config.setMaximumPoolSize(3);
                    config.setMinimumIdle(1);
                    config.setConnectionTimeout(10000);
                    config.setIdleTimeout(300000);
                    config.setMaxLifetime(600000);
                    ds = new HikariDataSource(config);
                    dataSourceCache.put(jdbcUrl, ds);
                    log.info("创建数据库连接池: {}", jdbcUrl);
                }
            }
        }
        return new JdbcTemplate(ds);
    }

    /**
     * 给 SQL 添加 LIMIT
     */
    private String addLimit(String sql, int limit) {
        String trimmed = sql.trim();
        // 去除末尾分号
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        // 已有 LIMIT 就不添加
        if (trimmed.toUpperCase().contains("LIMIT")) {
            return trimmed;
        }
        return trimmed + " LIMIT " + limit;
    }
}
