package io.yunxi.platform.tool.impl;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据库查询工具
 * <p>
 * 用于 Agent 执行只读 SQL 查询
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "database.enabled", havingValue = "true", matchIfMissing = false)
public class DatabaseTool {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(name = "database_query", description = "执行只读SQL查询，获取数据库数据")
    public String query(
            @ToolParam(name = "sql", description = "SQL查询语句（仅支持SELECT)") String sql,
            @ToolParam(name = "limit", description = "最大返回行数，默认为500") Integer limit) {
        long startTime = System.currentTimeMillis();
        try {
            if (limit == null || limit <= 0)
                limit = 500;

            String sqlUpper = sql.trim().toUpperCase();
            if (!sqlUpper.startsWith("SELECT")) {
                return "错误: 只允许执行SELECT查询";
            }

            String[] dangerousKeywords = { "DROP", "DELETE", "INSERT", "UPDATE", "ALTER", "CREATE", "TRUNCATE" };
            for (String keyword : dangerousKeywords) {
                if (sqlUpper.contains(keyword)) {
                    return "错误: SQL中包含危险关键字: " + keyword;
                }
            }

            if (!sqlUpper.contains("LIMIT") && limit > 0) {
                sql = sql + " LIMIT " + limit;
            }

            log.info("执行SQL查询: {}", sql);
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
            log.info("SQL查询成功，返回{}行", result.size());
            return String.format("{\"rowCount\":%d,\"data\":%s}", result.size(), result.toString());
        } catch (Exception e) {
            log.error("数据库查询失败", e);
            return "执行数据库查询失败: " + e.getMessage();
        }
    }
}
