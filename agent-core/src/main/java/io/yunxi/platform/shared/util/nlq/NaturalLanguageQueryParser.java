package io.yunxi.platform.shared.util.nlq;

import io.yunxi.platform.shared.config.MultiDatabaseConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言查询解析器
 * <p>
 * 解析自然语言描述，生成结构化的查询意图
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
public class NaturalLanguageQueryParser {

    private final MultiDatabaseConfig multiDatabaseConfig;
    private final String databaseId;

    /**
     * 创建自然语言查询解析器
     *
     * @param multiDatabaseConfig 多数据库配置
     * @param databaseId         数据库标识符（如 nutrition, finance, foodsafety）
     */
    public NaturalLanguageQueryParser(MultiDatabaseConfig multiDatabaseConfig, String databaseId) {
        this.multiDatabaseConfig = multiDatabaseConfig;
        this.databaseId = databaseId;
    }

    /**
     * 解析查询意图
     *
     * @param description 自然语言描述（如 "查看最近10个菜品"）
     * @return 查询意图
     */
    public QueryIntent parse(String description) {
        QueryIntent intent = new QueryIntent();
        String lowerDesc = description.toLowerCase();

        // 1. 识别表名
        String tableName = identifyTableName(lowerDesc);
        intent.setTable(tableName);

        // 2. 识别操作类型
        String operation = identifyOperation(lowerDesc);
        intent.setOperation(operation);

        // 3. 解析 LIMIT
        Integer limit = parseLimit(description, lowerDesc);
        intent.setLimit(limit);

        // 4. 解析 ORDER BY
        parseOrderBy(description, lowerDesc, intent);

        // 5. 解析 GROUP BY
        parseGroupBy(description, lowerDesc, intent);

        // 6. 解析 WHERE 条件
        parseWhereConditions(description, lowerDesc, intent);

        log.debug("解析结果: table={}, operation={}, limit={}, orderBy={}",
                intent.getTable(), intent.getOperation(), intent.getLimit(), intent.getOrderBy());

        return intent;
    }

    /**
     * 识别表名
     */
    private String identifyTableName(String lowerDesc) {
        MultiDatabaseConfig.DatabaseInfo dbInfo = multiDatabaseConfig.getDatabaseInfo(databaseId);
        if (dbInfo == null || dbInfo.getTableNameMappings() == null) {
            log.warn("数据库配置不存在: {}", databaseId);
            return null;
        }

        // 查找最匹配的表名
        String bestMatch = null;
        int maxScore = 0;

        for (Map.Entry<String, String> entry : dbInfo.getTableNameMappings().entrySet()) {
            String keyword = entry.getKey();
            String tableName = entry.getValue();

            int score = calculateMatchScore(lowerDesc, keyword);
            if (score > maxScore) {
                maxScore = score;
                bestMatch = tableName;
            }
        }

        if (bestMatch != null) {
            log.debug("识别表名: {} (匹配度: {})", bestMatch, maxScore);
            return bestMatch;
        }

        log.warn("无法从描述中识别表名: {}", lowerDesc);
        return null;
    }

    /**
     * 计算匹配分数
     */
    private int calculateMatchScore(String text, String keyword) {
        if (text.contains(keyword)) {
            // 完全匹配
            return 10;
        }

        // 检查部分匹配
        String[] words = text.split("\\s+");
        for (String word : words) {
            if (word.contains(keyword) || keyword.contains(word)) {
                return 5;
            }
        }

        return 0;
    }

    /**
     * 识别操作类型
     */
    private String identifyOperation(String lowerDesc) {
        MultiDatabaseConfig.DatabaseInfo dbInfo = multiDatabaseConfig.getDatabaseInfo(databaseId);
        if (dbInfo == null || dbInfo.getOperationMappings() == null) {
            // 默认操作
            return "query";
        }

        // 查找操作类型
        for (Map.Entry<String, String> entry : dbInfo.getOperationMappings().entrySet()) {
            if (lowerDesc.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return "query";
    }

    /**
     * 解析 LIMIT
     */
    private Integer parseLimit(String description, String lowerDesc) {
        // 匹配 "10条" 或 "10个"
        Pattern limitPattern = Pattern.compile("(\\d+)\\s*[条个]");
        Matcher limitMatcher = limitPattern.matcher(description);
        if (limitMatcher.find()) {
            return Integer.parseInt(limitMatcher.group(1));
        }

        if (lowerDesc.contains("最近")) {
            return 10;
        }

        if (lowerDesc.contains("所有") || lowerDesc.contains("全部")) {
            return null;
        }

        // 默认 20 条
        return 20;
    }

    /**
     * 解析 ORDER BY
     */
    private void parseOrderBy(String description, String lowerDesc, QueryIntent intent) {
        if (lowerDesc.contains("最新") || lowerDesc.contains("最近")) {
            intent.setOrderBy("update_time");
            intent.setOrderDirection("DESC");
        } else if (lowerDesc.contains("按") && lowerDesc.contains("排序")) {
            Pattern sortPattern = Pattern.compile("按(\\w+)排序");
            Matcher sortMatcher = sortPattern.matcher(description);
            if (sortMatcher.find()) {
                intent.setOrderBy(sortMatcher.group(1));
                intent.setOrderDirection("ASC");
            }
        }
    }

    /**
     * 解析 GROUP BY
     */
    private void parseGroupBy(String description, String lowerDesc, QueryIntent intent) {
        if (lowerDesc.contains("按") && (lowerDesc.contains("分组") || lowerDesc.contains("统计"))) {
            Pattern groupPattern = Pattern.compile("按(\\w+)(?:分组|统计)");
            Matcher groupMatcher = groupPattern.matcher(description);
            if (groupMatcher.find()) {
                intent.getGroupBy().add(groupMatcher.group(1));
            }
        }
    }

    /**
     * 解析 WHERE 条件
     */
    private void parseWhereConditions(String description, String lowerDesc, QueryIntent intent) {
        // 简单的条件解析
        // 例如：状态为启用
        if (lowerDesc.contains("状态为") || lowerDesc.contains("状态是")) {
            Pattern statusPattern = Pattern.compile("状态[为是](\\w+)");
            Matcher statusMatcher = statusPattern.matcher(description);
            if (statusMatcher.find()) {
                intent.getConditions().add("status = '" + statusMatcher.group(1) + "'");
            }
        }

        // 例如：id 为 123
        Pattern idPattern = Pattern.compile("id[为是]\\s*(\\d+)");
        Matcher idMatcher = idPattern.matcher(lowerDesc);
        if (idMatcher.find()) {
            intent.getConditions().add("id = " + idMatcher.group(1));
        }
    }

    /**
     * 查询意图
     */
    @Data
    public static class QueryIntent {
        /**
         * 目标表
         */
        private String table;

        /**
         * 操作类型（query, statistics, group, report）
         */
        private String operation = "query";

        /**
         * WHERE 条件列表
         */
        private List<String> conditions = new ArrayList<>();

        /**
         * ORDER BY 字段
         */
        private String orderBy;

        /**
         * 排序方向（ASC/DESC）
         */
        private String orderDirection;

        /**
         * 限制返回行数
         */
        private Integer limit = 20;

        /**
         * 分组字段
         */
        private List<String> groupBy = new ArrayList<>();

        /**
         * 聚合函数（COUNT, SUM, AVG 等）
         */
        private List<String> aggregates = new ArrayList<>();

        /**
         * 生成 SQL
         */
        public String toSql() {
            StringBuilder sql = new StringBuilder();

            switch (operation) {
                case "statistics":
                    // 统计查询
                    sql.append("SELECT COUNT(*) as count");

                    if (!groupBy.isEmpty()) {
                        for (String field : groupBy) {
                            sql.append(", ").append(field);
                        }
                        sql.append(", COUNT(*) as group_count");
                    }

                    sql.append(" FROM ").append(table);

                    if (!conditions.isEmpty()) {
                        sql.append(" WHERE ").append(String.join(" AND ", conditions));
                    }

                    if (!groupBy.isEmpty()) {
                        sql.append(" GROUP BY ").append(String.join(", ", groupBy));
                    }

                    break;

                case "group":
                    // 分组查询
                    sql.append("SELECT ");

                    if (!groupBy.isEmpty()) {
                        for (String field : groupBy) {
                            sql.append(field).append(", ");
                        }
                    }

                    sql.append("COUNT(*) as count FROM ").append(table);

                    if (!conditions.isEmpty()) {
                        sql.append(" WHERE ").append(String.join(" AND ", conditions));
                    }

                    if (!groupBy.isEmpty()) {
                        sql.append(" GROUP BY ").append(String.join(", ", groupBy));
                    }

                    if (orderBy != null) {
                        sql.append(" ORDER BY ").append(orderBy);
                        if (orderDirection != null) {
                            sql.append(" ").append(orderDirection);
                        }
                    }

                    break;

                case "report":
                default:
                    // 普通查询/报表查询
                    sql.append("SELECT * FROM ").append(table);

                    if (!conditions.isEmpty()) {
                        sql.append(" WHERE ").append(String.join(" AND ", conditions));
                    }

                    if (orderBy != null) {
                        sql.append(" ORDER BY ").append(orderBy);
                        if (orderDirection != null) {
                            sql.append(" ").append(orderDirection);
                        }
                    }

                    if (limit != null) {
                        sql.append(" LIMIT ").append(limit);
                    }

                    break;
            }

            return sql.toString();
        }
    }
}
