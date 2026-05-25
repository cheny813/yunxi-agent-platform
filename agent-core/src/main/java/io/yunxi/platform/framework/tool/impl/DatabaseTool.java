package io.yunxi.platform.framework.tool.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolExecutionException;
import io.yunxi.platform.framework.tool.ToolInput;
import io.yunxi.platform.framework.tool.ToolResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库查询工具
 * <p>
 * 用于Agent执行只读SQL查询
 * </p>
 * <p>
 * 注意：需要配置数据源才可用
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "database.enabled", havingValue = "true", matchIfMissing = false)
public class DatabaseTool implements Tool {

    /** JDBC 模板 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造数据库查询工具
     *
     * @param jdbcTemplate JDBC 模板
     */
    public DatabaseTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getName() {
        return "database_query";
    }

    @Override
    public String getDescription() {
        return "执行只读SQL查询，获取数据库数据";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "sql": {
                            "type": "string",
                            "description": "SQL查询语句"
                        },
                        "limit": {
                            "type": "integer",
                            "default": 500,
                            "description": "最大返回行数"
                        }
                    },
                    "required": ["sql"]
                }
                """;
    }

    @Override
    public ToolResult execute(ToolInput input) throws ToolExecutionException {
        long startTime = System.currentTimeMillis();

        try {
            String sql = input.getString("sql");
            int limit = input.getInt("limit", 500);

            // 安全检查：只允许SELECT查询
            String sqlUpper = sql.trim().toUpperCase();
            if (!sqlUpper.startsWith("SELECT")) {
                if (log.isWarnEnabled()) {
                    log.warn("拒绝非SELECT查询: {}", sql);
                }
                return ToolResult.error("只允许执行SELECT查询");
            }

            // 检查危险的SQL关键字
            String[] dangerousKeywords = { "DROP", "DELETE", "INSERT", "UPDATE", "ALTER", "CREATE", "TRUNCATE" };
            for (String keyword : dangerousKeywords) {
                if (sqlUpper.contains(keyword)) {
                    if (log.isWarnEnabled()) {
                        log.warn("检测到危险关键字: {}", keyword);
                    }
                    return ToolResult.error("SQL中包含危险关键字: " + keyword);
                }
            }

            // 添加LIMIT限制
            if (!sqlUpper.contains("LIMIT") && limit > 0) {
                sql = sql + " LIMIT " + limit;
            }

            if (log.isInfoEnabled()) {
                log.info("执行SQL查询: {}", sql);
            }

            // 执行查询
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);

            // 构建输出
            Map<String, Object> output = new HashMap<>();
            output.put("sql", sql);
            output.put("rowCount", result.size());
            output.put("data", result);

            if (log.isInfoEnabled()) {
                log.info("SQL查询成功，返回{}行", result.size());
            }

            ToolResult toolResult = ToolResult.success(output);
            toolResult.setDurationMs(System.currentTimeMillis() - startTime);
            return toolResult;

        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("数据库查询失败", e);
            }
            throw new ToolExecutionException(getName(), "执行数据库查询失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isEnabled() {
        return jdbcTemplate != null;
    }
}
