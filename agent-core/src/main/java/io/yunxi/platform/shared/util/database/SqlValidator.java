package io.yunxi.platform.shared.util.database;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Pattern;

/**
 * SQL验证器 - 验证SQL语法和字段正确性
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
public class SqlValidator {

    private final SchemaInspector inspector;

    // 常见错误字段映射
    private static final Map<String, String> COMMON_FIELD_ERRORS = Map.of(
            "is_deleted", "deleted",
            "parent_id", "pid",
            "create_time", "createTime",
            "update_time", "updateTime"
    );

    public SqlValidator(SchemaInspector inspector) {
        this.inspector = inspector;
    }

    /**
     * 验证SQL
     */
    public ValidationResult validate(String sql) {
        ValidationResult result = new ValidationResult();
        result.setSql(sql);
        result.setValid(true);

        try {
            // 提取表名
            String tableName = extractTableName(sql);
            if (tableName == null) {
                result.addWarning("无法从SQL中提取表名，跳过字段验证");
                return result;
            }
            result.setTableName(tableName);

            // 获取表结构
            TableSchema schema = inspector.describeTable(tableName);
            if (schema == null) {
                result.addWarning("无法获取表结构: " + tableName);
                return result;
            }

            // 提取SQL中的字段
            Set<String> sqlFields = extractFieldsFromSql(sql);

            // 验证每个字段
            for (String field : sqlFields) {
                validateField(field, schema, result);
            }

            // 添加智能建议
            addSmartSuggestions(sql, schema, result);

        } catch (Exception e) {
            log.error("验证SQL失败", e);
            result.addError("验证过程发生异常: " + e.getMessage());
        }

        return result;
    }

    /**
     * 批量验证SQL
     */
    public Map<String, ValidationResult> validateBatch(Map<String, String> sqls) {
        Map<String, ValidationResult> results = new HashMap<>();

        for (Map.Entry<String, String> entry : sqls.entrySet()) {
            ValidationResult result = validate(entry.getValue());
            results.put(entry.getKey(), result);
        }

        return results;
    }

    /**
     * 提取表名
     */
    private String extractTableName(String sql) {
        try {
            String normalizedSql = sql.toLowerCase().replaceAll("\\s+", " ");

            int fromIndex = normalizedSql.indexOf(" from ");
            if (fromIndex == -1) {
                return null;
            }

            String fromClause = sql.substring(fromIndex + 6);
            String[] parts = fromClause.split("[,\\s]+");

            if (parts.length > 0) {
                return parts[0].replace("`", "");
            }

        } catch (Exception e) {
            log.error("提取表名失败", e);
        }

        return null;
    }

    /**
     * 从SQL中提取字段
     */
    private Set<String> extractFieldsFromSql(String sql) {
        Set<String> fields = new HashSet<>();

        try {
            String normalizedSql = sql.toLowerCase().replaceAll("\\s+", " ");

            int selectIndex = normalizedSql.indexOf("select ");
            int fromIndex = normalizedSql.indexOf(" from ");

            if (selectIndex == -1 || fromIndex == -1) {
                return fields;
            }

            String fieldsPart = normalizedSql.substring(selectIndex + 7, fromIndex).trim();
            String[] fieldItems = fieldsPart.split(",");

            for (String item : fieldItems) {
                item = item.trim();

                // 跳过聚合函数
                if (item.contains("(") && !item.equals("(")) {
                    continue;
                }

                String fieldName = item;
                if (item.contains(" as ")) {
                    fieldName = item.split(" as ")[0].trim();
                }

                if (fieldName.contains(".")) {
                    fieldName = fieldName.split("\\.")[1];
                }

                if (!fieldName.isEmpty() && !fieldName.equals("*")) {
                    fields.add(fieldName);
                }
            }

            // 提取WHERE条件中的字段
            fields.addAll(extractWhereFields(sql));

        } catch (Exception e) {
            log.error("提取SQL字段失败", e);
        }

        return fields;
    }

    /**
     * 提取WHERE条件中的字段
     */
    private Set<String> extractWhereFields(String sql) {
        Set<String> fields = new HashSet<>();

        try {
            String normalizedSql = sql.toLowerCase();

            int whereIndex = normalizedSql.indexOf(" where ");
            if (whereIndex == -1) {
                return fields;
            }

            String whereClause = sql.substring(whereIndex + 7);

            // 简单匹配: field = value
            Pattern pattern = Pattern.compile("(\\w+)\\s*[=<>!]");
            java.util.regex.Matcher matcher = pattern.matcher(whereClause);

            while (matcher.find()) {
                String field = matcher.group(1);
                if (field.contains(".")) {
                    field = field.split("\\.")[1];
                }
                fields.add(field);
            }

        } catch (Exception e) {
            log.error("提取WHERE字段失败", e);
        }

        return fields;
    }

    /**
     * 验证单个字段
     */
    private void validateField(String field, TableSchema schema, ValidationResult result) {
        String lowerField = field.toLowerCase();

        if (lowerField.equals("*")) {
            return;
        }

        if (!schema.hasColumn(lowerField)) {
            // 检查常见错误映射
            if (COMMON_FIELD_ERRORS.containsKey(lowerField)) {
                String correctField = COMMON_FIELD_ERRORS.get(lowerField);
                result.addError(String.format(
                        "字段 '%s' 不存在，可能应为 '%s'",
                        field, correctField
                ));
            }

            // 检查相似字段
            String similarField = schema.findSimilarColumn(field);
            if (similarField != null) {
                result.addSuggestion(String.format(
                        "字段 '%s' 不存在，是否是指 '%s'？",
                        field, similarField
                ));
            } else {
                result.addError(String.format(
                        "字段 '%s' 在表 '%s' 中不存在。可用字段: %s",
                        field, schema.getTableName(), schema.getColumns()
                ));
            }
        }
    }

    /**
     * 添加智能建议
     */
    private void addSmartSuggestions(String sql, TableSchema schema, ValidationResult result) {
        Set<String> sqlFields = extractFieldsFromSql(sql);

        for (String field : sqlFields) {
            String lowerField = field.toLowerCase();

            // 检查是否在WHERE条件中使用了非索引字段
            if (sql.toLowerCase().contains(" where ") &&
                    lowerField.contains("=") &&
                    !schema.isPrimaryKey(lowerField)) {

                result.addSuggestion(String.format(
                        "字段 '%s' 在WHERE条件中使用，如果不是主键，考虑添加索引以提高查询性能",
                        field
                ));
            }
        }
    }
}
