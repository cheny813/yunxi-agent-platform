package io.yunxi.platform.agent.text2sql.alignment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * SQL 对齐器
 * <p>
 * 自动修复 SQL 中的常见错误，如字段名错误、缺少条件等
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
public class SqlAligner {

    private static final Logger log = LoggerFactory.getLogger(SqlAligner.class);

    // 常见字段名错误映射
    private static final Map<String, String> COMMON_FIELD_ERRORS = new HashMap<>();
    static {
        COMMON_FIELD_ERRORS.put("is_deleted", "deleted");
        COMMON_FIELD_ERRORS.put("parent_id", "pid");
        COMMON_FIELD_ERRORS.put("isdeleted", "deleted");
        COMMON_FIELD_ERRORS.put("create_time", "createTime");
        COMMON_FIELD_ERRORS.put("update_time", "updateTime");
    }

    /**
     * 对齐 SQL
     *
     * @param sql     原始 SQL
     * @param schema  数据库 Schema（可选）
     * @param columns 列信息（可选）
     * @return 对齐后的 SQL
     */
    public String alignSql(String sql, Object schema, Object columns) {
        if (sql == null || sql.trim().isEmpty()) {
            return sql;
        }

        String aligned = sql;

        try {
            // 1. 修复字段名错误
            aligned = fixColumnNames(aligned);

            // 2. 添加缺失的 deleted=0 条件
            aligned = addMissingConditions(aligned);

            // 3. 添加缺失的 LIMIT
            aligned = addMissingLimit(aligned);

            log.debug("SQL 对齐完成: 原始={}, 对齐后={}", sql, aligned);

        } catch (Exception e) {
            log.warn("SQL 对齐失败: sql={}", sql, e);
            return sql;
        }

        return aligned;
    }

    /**
     * 修复字段名错误
     */
    private String fixColumnNames(String sql) {
        String result = sql;

        // 修复常见字段名错误
        for (Map.Entry<String, String> entry : COMMON_FIELD_ERRORS.entrySet()) {
            String wrongName = entry.getKey();
            String correctName = entry.getValue();

            // 使用正则表达式替换字段名，避免替换字符串中的内容
            // 匹配字段名：前面是空格、逗号、等号、操作符等
            String pattern = "(?<=[\\s,=<>!()])" + wrongName + "(?=[\\s,=<>!)])";
            result = result.replaceAll(pattern, correctName);
        }

        return result;
    }

    /**
     * 添加缺失的 deleted=0 条件
     */
    private String addMissingConditions(String sql) {
        // 检查是否已经有 WHERE deleted=0 或类似条件
        if (sql.toLowerCase().contains("where") &&
                (sql.toLowerCase().contains("deleted") || sql.toLowerCase().contains("del"))) {
            return sql;
        }

        // 如果没有 WHERE 子句，不添加
        if (!sql.toLowerCase().contains("where")) {
            return sql;
        }

        // 检查是否需要添加 deleted=0
        String lowerSql = sql.toLowerCase();
        if (lowerSql.contains("select") && !lowerSql.contains("update") && !lowerSql.contains("delete")
                && !lowerSql.contains("deleted")) {
            // 简单的逻辑：如果删除语句中没有包含deleted字段，则添加WHERE deleted=0条件
            if (lowerSql.contains("where")) {
                sql = sql.replaceAll("\\bwhere\\b(.*)$", "where $1 and deleted=0");
            } else {
                // 替换FROM子句，添加WHERE条件
                sql = sql.replaceAll("\\bfrom\\b(.*)$", "from $1 where deleted=0");
            }
        }

        return sql;
    }

    /**
     * 添加缺失的 LIMIT
     */
    private String addMissingLimit(String sql) {
        String trimmed = sql.trim();

        // 检查是否已经有 LIMIT
        if (trimmed.toLowerCase().matches(".*limit\\s+\\d+.*")) {
            return sql;
        }

        // 对于 SELECT 语句，添加默认 LIMIT
        if (trimmed.toLowerCase().startsWith("select")) {
            return trimmed + " LIMIT 1000";
        }

        return sql;
    }
}
