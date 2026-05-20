package io.yunxi.platform.business.nutrition.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库Schema检查器
 * <p>
 * 通过MCP动态查询数据库表结构，验证SQL字段名的正确性
 * 自动检测字段名错误、字段不存在等问题
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
public class DatabaseSchemaInspector {

    /** JSON 序列化/反序列化工具 */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 表结构缓存（避免重复查询）
     */
    private static final Map<String, TableSchema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    /**
     * 表结构信息
     */
    @lombok.Data
    public static class TableSchema {
        /**
         * 表名
         */
        private String tableName;

        /**
         * 所有字段名（大小写不敏感）
         */
        private Set<String> columns = new HashSet<>();

        /**
         * 字段详细信息
         */
        private Map<String, ColumnInfo> columnDetails = new HashMap<>();
    }

    /**
     * 字段信息
     */
    @lombok.Data
    public static class ColumnInfo {
        /**
         * 字段名
         */
        private String name;

        /**
         * 字段类型
         */
        private String type;

        /**
         * 是否允许NULL
         */
        private Boolean nullable;

        /**
         * 默认值
         */
        private String defaultValue;

        /**
         * 注释
         */
        private String comment;
    }

    /**
     * SQL验证结果
     */
    @lombok.Data
    public static class ValidationResult {
        /**
         * 是否验证通过
         */
        private boolean valid;

        /**
         * 错误信息列表
         */
        private List<String> errors = new ArrayList<>();

        /**
         * 警告信息列表
         */
        private List<String> warnings = new ArrayList<>();

        /**
         * 建议信息列表
         */
        private List<String> suggestions = new ArrayList<>();

        /**
         * 添加错误信息，同时将验证结果标记为无效
         *
         * @param error 错误信息
         */
        public void addError(String error) {
            this.valid = false;
            this.errors.add(error);
        }

        /**
         * 添加警告信息
         *
         * @param warning 警告信息
         */
        public void addWarning(String warning) {
            this.warnings.add(warning);
        }

        /**
         * 添加建议信息
         *
         * @param suggestion 建议信息
         */
        public void addSuggestion(String suggestion) {
            this.suggestions.add(suggestion);
        }
    }

    /**
     * 从describe_table的响应中解析表结构
     *
     * @param describeResponse describe_table的MCP响应
     * @return 表结构信息
     */
    public static TableSchema parseDescribeResponse(String describeResponse) {
        TableSchema schema = new TableSchema();

        try {
            JsonNode jsonResponse = objectMapper.readTree(describeResponse);
            if (jsonResponse == null) {
                log.warn("describe响应为空");
                return schema;
            }

            // 解析MCP响应格式
            JsonNode result = jsonResponse.get("result");
            if (result == null || !result.isObject()) {
                log.warn("MCP响应中没有result字段");
                return schema;
            }

            JsonNode content = result.get("content");
            if (content == null || !content.isArray() || content.isEmpty()) {
                log.warn("MCP响应中没有content数组");
                return schema;
            }

            // 获取第一段文本内容
            JsonNode textContent = content.get(0);
            if (textContent == null) {
                log.warn("MCP响应中没有文本内容");
                return schema;
            }

            JsonNode textNode = textContent.get("text");
            if (textNode == null || !textNode.isTextual()) {
                log.warn("MCP响应中的文本为空");
                return schema;
            }

            String text = textNode.asText();

            // 解析DESCRIBE TABLE的输出格式
            // 格式示例：
            // Field Type Null Key Default Extra
            // ------------------ ------------------------ -------- ------- ----------
            // -------
            // id bigint(20) NO PRI NULL auto_increment
            // name varchar(64) YES NULL
            // deleted int(11) NO 0

            String[] lines = text.split("\n");
            for (String line : lines) {
                line = line.trim();

                // 跳过表头和分隔线
                if (line.startsWith("Field") || line.startsWith("---") || line.isEmpty()) {
                    continue;
                }

                // 解析字段行
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String columnName = parts[0];
                    String columnType = parts.length > 1 ? parts[1] : "";

                    ColumnInfo columnInfo = new ColumnInfo();
                    columnInfo.setName(columnName);
                    columnInfo.setType(columnType);

                    schema.getColumns().add(columnName.toLowerCase());
                    schema.getColumnDetails().put(columnName.toLowerCase(), columnInfo);
                }
            }

            if (!schema.getColumns().isEmpty()) {
                schema.setTableName("unknown");
                log.info("成功解析表结构，字段数: {}", schema.getColumns().size());
            }

        } catch (Exception e) {
            log.error("解析describe_table响应失败", e);
        }

        return schema;
    }

    /**
     * 验证SQL中的字段是否存在
     *
     * @param sql       SQL语句
     * @param schema    表结构信息
     * @param tableName 表名
     * @return 验证结果
     */
    public static ValidationResult validateSqlFields(String sql, TableSchema schema, String tableName) {
        ValidationResult result = new ValidationResult();
        result.setValid(true);

        if (schema == null || schema.getColumns().isEmpty()) {
            result.addWarning("表结构信息为空，无法验证字段");
            return result;
        }

        try {
            // 提取SQL中的字段名
            // 简单匹配：SELECT后的字段名（不包括函数和表别名）
            Set<String> sqlFields = extractFieldsFromSql(sql);

            // 验证每个字段
            for (String field : sqlFields) {
                // 跳过通配符
                if (field.equals("*")) {
                    continue;
                }

                // 检查字段是否存在
                if (!schema.getColumns().contains(field.toLowerCase())) {
                    result.addError(String.format(
                            "字段 '%s' 在表 '%s' 中不存在。可用字段: %s",
                            field, tableName, schema.getColumns()));
                }
            }

            // 检查WHERE条件中的字段
            validateWhereClauseFields(sql, schema, tableName, result);

        } catch (Exception e) {
            log.error("验证SQL字段失败: {}", sql, e);
            result.addWarning("验证SQL字段时发生异常: " + e.getMessage());
        }

        return result;
    }

    /**
     * 从SQL中提取字段名
     *
     * @param sql SQL语句
     * @return 字段名集合
     */
    public static Set<String> extractFieldsFromSql(String sql) {
        Set<String> fields = new HashSet<>();

        try {
            // 转换为小写并去除多余空格
            String normalizedSql = sql.toLowerCase().replaceAll("\\s+", " ");

            // 找到SELECT和FROM之间的部分
            int selectIndex = normalizedSql.indexOf("select ");
            int fromIndex = normalizedSql.indexOf(" from ");

            if (selectIndex == -1 || fromIndex == -1) {
                return fields;
            }

            String fieldsPart = normalizedSql.substring(selectIndex + 7, fromIndex).trim();

            // 处理字段列表
            String[] fieldItems = fieldsPart.split(",");

            for (String item : fieldItems) {
                item = item.trim();

                // 跳过聚合函数
                if (item.contains("count(") || item.contains("sum(") ||
                        item.contains("avg(") || item.contains("max(") ||
                        item.contains("min(") || item.contains("distinct ")) {
                    continue;
                }

                // 提取字段名（处理AS别名）
                String fieldName = item;
                if (item.contains(" as ")) {
                    fieldName = item.split(" as ")[0].trim();
                }

                // 去除表别名（如d.name -> name）
                if (fieldName.contains(".")) {
                    fieldName = fieldName.split("\\.")[1];
                }

                // 去除函数调用括号
                fieldName = fieldName.replaceAll("\\(.*\\)", "");

                if (!fieldName.isEmpty() && !fieldName.equals("*")) {
                    fields.add(fieldName);
                }
            }

        } catch (Exception e) {
            log.error("提取SQL字段失败", e);
        }

        return fields;
    }

    /**
     * 验证WHERE条件中的字段
     *
     * @param sql       SQL语句
     * @param schema    表结构信息
     * @param tableName 表名
     * @param result    验证结果
     */
    public static void validateWhereClauseFields(String sql, TableSchema schema, String tableName,
            ValidationResult result) {
        try {
            String normalizedSql = sql.toLowerCase();

            // 找到WHERE子句
            int whereIndex = normalizedSql.indexOf(" where ");
            if (whereIndex == -1) {
                return;
            }

            String whereClause = normalizedSql.substring(whereIndex + 7);

            // 去除子查询和复杂条件（简化处理）
            // 只处理简单的 field = value 格式
            String[] conditions = whereClause.split(" and | or ");

            for (String condition : conditions) {
                condition = condition.trim();

                // 提取字段名（在等号、LIKE、IN等操作符前的部分）
                String[] operators = { "=", "!=", "<>", "like", "in ", "is " };
                for (String op : operators) {
                    int opIndex = condition.indexOf(op);
                    if (opIndex != -1) {
                        String field = condition.substring(0, opIndex).trim();

                        // 去除表别名
                        if (field.contains(".")) {
                            field = field.split("\\.")[1];
                        }

                        // 去除函数调用
                        field = field.replaceAll("\\(.*\\)", "");

                        if (!field.isEmpty() && !field.equals("*")) {
                            if (!schema.getColumns().contains(field.toLowerCase())) {
                                result.addError(String.format(
                                        "WHERE条件中的字段 '%s' 在表 '%s' 中不存在。可用字段: %s",
                                        field, tableName, schema.getColumns()));
                            }
                        }

                        break;
                    }
                }
            }

        } catch (Exception e) {
            log.error("验证WHERE条件字段失败", e);
        }
    }

    /**
     * 清除所有缓存的表结构
     */
    public static void clearCache() {
        SCHEMA_CACHE.clear();
        log.info("表结构缓存已清除");
    }

    /**
     * 获取缓存的表结构
     *
     * @param tableName 表名
     * @return 表结构信息，不存在则返回null
     */
    public static TableSchema getCachedSchema(String tableName) {
        return SCHEMA_CACHE.get(tableName.toLowerCase());
    }

    /**
     * 缓存表结构
     *
     * @param tableName 表名
     * @param schema    表结构信息
     */
    public static void cacheSchema(String tableName, TableSchema schema) {
        SCHEMA_CACHE.put(tableName.toLowerCase(), schema);
        log.info("表结构已缓存: {}", tableName);
    }

    /**
     * 批量验证多个SQL语句
     *
     * @param sqls   SQL语句列表
     * @param schema 表结构信息
     * @return 验证结果列表，与输入SQL语句一一对应
     */
    public static List<ValidationResult> validateMultipleSqls(List<String> sqls, TableSchema schema) {
        List<ValidationResult> results = new ArrayList<>();

        for (int i = 0; i < sqls.size(); i++) {
            ValidationResult result = validateSqlFields(sqls.get(i), schema, "unknown");
            if (!result.isValid()) {
                result.addError(String.format("SQL #%d 验证失败", i + 1));
            }
            results.add(result);
        }

        return results;
    }

    /**
     * 生成表结构报告
     *
     * @param schema 表结构信息
     * @return 格式化的表结构报告
     */
    public static String generateSchemaReport(TableSchema schema) {
        if (schema == null) {
            return "表结构信息为空";
        }

        StringBuilder report = new StringBuilder();
        report.append("表结构报告\n");
        report.append("=".repeat(50)).append("\n");

        if (schema.getTableName() != null) {
            report.append("表名: ").append(schema.getTableName()).append("\n");
        }

        report.append("字段数: ").append(schema.getColumns().size()).append("\n");
        report.append("\n");

        report.append("字段列表:\n");
        report.append("-".repeat(50)).append("\n");

        for (String column : schema.getColumns()) {
            ColumnInfo info = schema.getColumnDetails().get(column);
            if (info != null) {
                report.append(String.format("  %-20s %-20s %s\n",
                        column,
                        info.getType() != null ? info.getType() : "",
                        info.getComment() != null ? "# " + info.getComment() : ""));
            } else {
                report.append(String.format("  %s\n", column));
            }
        }

        return report.toString();
    }
}
