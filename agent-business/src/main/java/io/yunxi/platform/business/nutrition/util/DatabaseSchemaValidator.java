package io.yunxi.platform.business.nutrition.util;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据库Schema验证器
 * <p>
 * 提供SQL验证、常见错误检测、智能建议等功能
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
public class DatabaseSchemaValidator {

    /**
     * 常见错误字段映射
     * 错误字段名 -> 正确字段名
     */
    private static final Map<String, String> COMMON_FIELD_ERRORS = new HashMap<>();

    static {
        // 逻辑删除字段
        COMMON_FIELD_ERRORS.put("is_deleted", "deleted");

        // 父级ID字段
        COMMON_FIELD_ERRORS.put("parent_id", "pid");

        // 创建时间字段
        COMMON_FIELD_ERRORS.put("create_time", "createTime");
        COMMON_FIELD_ERRORS.put("created_at", "createTime");

        // 更新时间字段
        COMMON_FIELD_ERRORS.put("update_time", "updateTime");
        COMMON_FIELD_ERRORS.put("updated_at", "updateTime");
    }

    /**
     * 验证SQL字段并提供建议
     *
     * @param sql       SQL语句
     * @param schema    表结构信息
     * @param tableName 表名
     * @return 验证结果，包含错误、警告和智能建议
     */
    public static DatabaseSchemaInspector.ValidationResult validateSqlFields(
            String sql,
            DatabaseSchemaInspector.TableSchema schema,
            String tableName) {

        DatabaseSchemaInspector.ValidationResult result = DatabaseSchemaInspector.validateSqlFields(sql, schema, tableName);

        // 添加智能建议
        addSmartSuggestions(sql, schema, result);

        return result;
    }

    /**
     * 添加智能建议
     *
     * <p>
     * 对SQL中不存在的字段，提供三种类型的建议：
     * <ol>
     * <li>常见错误映射（如 is_deleted → deleted）</li>
     * <li>大小写不匹配提示</li>
     * <li>基于编辑距离的相似字段推荐</li>
     * </ol>
     * </p>
     *
     * @param sql    SQL语句
     * @param schema 表结构信息
     * @param result 验证结果，建议会追加到此对象
     */
    private static void addSmartSuggestions(String sql, DatabaseSchemaInspector.TableSchema schema,
                                             DatabaseSchemaInspector.ValidationResult result) {

        try {
            Set<String> sqlFields = extractAllFieldsFromSql(sql);

            for (String sqlField : sqlFields) {
                String lowerField = sqlField.toLowerCase();

                // 跳过通配符和函数
                if (lowerField.equals("*") || lowerField.contains("(")) {
                    continue;
                }

                // 如果字段不存在，检查是否是常见的错误字段名
                if (!schema.getColumns().contains(lowerField)) {
                    // 检查常见错误映射
                    if (COMMON_FIELD_ERRORS.containsKey(lowerField)) {
                        String correctField = COMMON_FIELD_ERRORS.get(lowerField);
                        result.addSuggestion(String.format(
                                "字段 '%s' 可能应为 '%s'（常见错误映射）",
                                sqlField, correctField
                        ));
                    }

                    // 检查大小写错误
                    for (String schemaField : schema.getColumns()) {
                        if (sqlField.equalsIgnoreCase(schemaField)) {
                            result.addSuggestion(String.format(
                                    "字段 '%s' 大小写不匹配，表结构中是 '%s'",
                                    sqlField, schemaField
                            ));
                            break;
                        }
                    }

                    // 检查相似字段（编辑距离）
                    String similarField = findSimilarField(lowerField, schema.getColumns());
                    if (similarField != null) {
                        result.addSuggestion(String.format(
                                "字段 '%s' 不存在，是否是指 '%s'？",
                                sqlField, similarField
                        ));
                    }
                }
            }

        } catch (Exception e) {
            log.error("添加智能建议失败", e);
        }
    }

    /**
     * 从SQL中提取所有字段（包括SELECT和WHERE中的字段）
     *
     * @param sql SQL语句
     * @return 所有字段名集合
     */
    private static Set<String> extractAllFieldsFromSql(String sql) {
        Set<String> fields = DatabaseSchemaInspector.extractFieldsFromSql(sql);

        // 额外提取WHERE条件中的字段
        fields.addAll(extractWhereClauseFields(sql));

        return fields;
    }

    /**
     * 从WHERE子句中提取字段
     *
     * @param sql SQL语句
     * @return 字段名集合
     */
    private static Set<String> extractWhereClauseFields(String sql) {
        Set<String> fields = new HashSet<>();

        try {
            String normalizedSql = sql.toLowerCase();

            int whereIndex = normalizedSql.indexOf(" where ");
            if (whereIndex == -1) {
                return fields;
            }

            String whereClause = sql.substring(whereIndex + 7);

            // 匹配简单的 field = value 模式
            Pattern pattern = Pattern.compile("(\\w+)\\s*[=<>!]|\\b(\\w+)\\s+like|\\b(\\w+)\\s+in", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(whereClause);

            while (matcher.find()) {
                String field = matcher.group(1) != null ? matcher.group(1) :
                               matcher.group(2) != null ? matcher.group(2) : matcher.group(3);

                if (field != null) {
                    // 去除表别名
                    if (field.contains(".")) {
                        field = field.split("\\.")[1];
                    }
                    fields.add(field);
                }
            }

        } catch (Exception e) {
            log.error("提取WHERE子句字段失败", e);
        }

        return fields;
    }

    /**
     * 查找相似的字段名（使用简单的编辑距离）
     *
     * @param target     目标字段名
     * @param candidates 候选字段名集合
     * @return 最相似的字段名，如果没有则返回null
     */
    private static String findSimilarField(String target, Set<String> candidates) {
        String mostSimilar = null;
        int minDistance = Integer.MAX_VALUE;

        for (String candidate : candidates) {
            int distance = calculateLevenshteinDistance(target, candidate);
            if (distance < minDistance && distance <= 3) { // 编辑距离阈值
                minDistance = distance;
                mostSimilar = candidate;
            }
        }

        return mostSimilar;
    }

    /**
     * 计算Levenshtein编辑距离
     *
     * @param str1 字符串1
     * @param str2 字符串2
     * @return 编辑距离
     */
    private static int calculateLevenshteinDistance(String str1, String str2) {
        int len1 = str1.length();
        int len2 = str2.length();

        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = str1.charAt(i - 1) == str2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[len1][len2];
    }

    /**
     * 批量验证SQL并生成汇总报告
     *
     * @param sqls      SQL语句映射（名称 → SQL语句）
     * @param schemaMap 表名 → 表结构映射
     * @return 格式化的汇总报告文本
     */
    public static String generateBatchValidationReport(
            Map<String, String> sqls,
            Map<String, DatabaseSchemaInspector.TableSchema> schemaMap) {

        StringBuilder report = new StringBuilder();
        report.append("SQL验证汇总报告\n");
        report.append("=" .repeat(60)).append("\n\n");

        int totalSqls = sqls.size();
        int passedSqls = 0;
        int failedSqls = 0;

        for (Map.Entry<String, String> entry : sqls.entrySet()) {
            String sqlName = entry.getKey();
            String sql = entry.getValue();

            // 提取表名
            String tableName = extractTableName(sql);
            if (tableName == null) {
                report.append(String.format("[%s] ❌ 无法提取表名\n\n", sqlName));
                failedSqls++;
                continue;
            }

            DatabaseSchemaInspector.TableSchema schema = schemaMap.get(tableName);
            if (schema == null) {
                report.append(String.format("[%s] ⚠️  表结构未加载: %s\n\n", sqlName, tableName));
                continue;
            }

            // 验证SQL
            DatabaseSchemaInspector.ValidationResult result = validateSqlFields(sql, schema, tableName);

            // 生成报告
            report.append(String.format("[%s] %s\n", sqlName, result.isValid() ? "✅ 通过" : "❌ 失败"));
            report.append(String.format("  表名: %s\n", tableName));
            report.append(String.format("  SQL: %s\n", sql));

            if (!result.getErrors().isEmpty()) {
                report.append("  错误:\n");
                for (String error : result.getErrors()) {
                    report.append(String.format("    - %s\n", error));
                }
            }

            if (!result.getWarnings().isEmpty()) {
                report.append("  警告:\n");
                for (String warning : result.getWarnings()) {
                    report.append(String.format("    - %s\n", warning));
                }
            }

            if (!result.getSuggestions().isEmpty()) {
                report.append("  建议:\n");
                for (String suggestion : result.getSuggestions()) {
                    report.append(String.format("    - %s\n", suggestion));
                }
            }

            report.append("\n");

            if (result.isValid()) {
                passedSqls++;
            } else {
                failedSqls++;
            }
        }

        // 生成汇总
        report.append("=" .repeat(60)).append("\n");
        report.append("汇总:\n");
        report.append(String.format("  总SQL数: %d\n", totalSqls));
        report.append(String.format("  通过: %d ✅\n", passedSqls));
        report.append(String.format("  失败: %d ❌\n", failedSqls));
        report.append(String.format("  通过率: %.1f%%\n", totalSqls > 0 ? (passedSqls * 100.0 / totalSqls) : 0));

        return report.toString();
    }

    /**
     * 从SQL中提取表名（简单实现）
     *
     * @param sql SQL语句
     * @return 表名
     */
    private static String extractTableName(String sql) {
        try {
            String normalizedSql = sql.toLowerCase().replaceAll("\\s+", " ");

            int fromIndex = normalizedSql.indexOf(" from ");
            if (fromIndex == -1) {
                return null;
            }

            String fromClause = sql.substring(fromIndex + 6);
            String[] parts = fromClause.split("[,\\s]+");

            if (parts.length > 0) {
                String tableName = parts[0];
                return tableName.replace("`", "");
            }

        } catch (Exception e) {
            log.error("提取表名失败", e);
        }

        return null;
    }
}
