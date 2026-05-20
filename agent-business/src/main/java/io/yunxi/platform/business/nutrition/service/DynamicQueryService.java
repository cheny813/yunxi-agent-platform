package io.yunxi.platform.business.nutrition.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态查询服务
 * <p>
 * 支持根据用户描述，动态从数据库中查询数据，生成报表、列表、统计信息等
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class DynamicQueryService {

    /** REST 请求模板 */
    private final RestTemplate restTemplate = new RestTemplate();
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** MCP 数据库服务主机地址 */
    @Value("${dish-sync.mcp-database.host:localhost}")
    private String mcpDbHost;

    /** MCP 数据库服务端口 */
    @Value("${dish-sync.mcp-database.port:40101}")
    private int mcpDbPort;

    /**
     * 查询结果
     */
    @Data
    public static class QueryResult {
        /**
         * 查询的表名
         */
        private String tableName;

        /**
         * SQL语句
         */
        private String sql;

        /**
         * 返回的数据行数
         */
        private int rowCount;

        /**
         * 数据列表
         */
        private List<Map<String, Object>> data = new ArrayList<>();

        /**
         * 查询耗时（毫秒）
         */
        private long duration;

        /**
         * 错误信息
         */
        private String error;

        /**
         * 表结构信息
         */
        private QueryResult.TableSchemaInfo schema;

        @Data
        public static class TableSchemaInfo {
            /** 列名列表 */
            private List<String> columns = new ArrayList<>();
            /** 列名到类型的映射 */
            private Map<String, String> columnTypes = new HashMap<>();
        }
    }

    /**
     * 根据自然语言描述查询数据
     *
     * @param description 自然语言描述（如"查看最近10个菜品"）
     * @return 查询结果
     */
    public QueryResult queryByDescription(String description) {
        QueryResult result = new QueryResult();
        long startTime = System.currentTimeMillis();

        try {
            log.info("解析查询描述: {}", description);

            // 1. 解析查询意图
            QueryIntent intent = parseQueryIntent(description);
            log.info("解析结果: 表={}, 操作={}, 限制={}", intent.table, intent.operation, intent.limit);

            // 2. 生成SQL
            String sql = generateSql(intent);
            result.setSql(sql);
            result.setTableName(intent.table);

            log.info("生成的SQL: {}", sql);

            // 3. 执行查询
            List<Map<String, Object>> data = executeQuery(sql);
            result.setData(data);
            result.setRowCount(data.size());

            // 4. 获取表结构
            QueryResult.TableSchemaInfo schema = getTableSchema(intent.table);
            result.setSchema(schema);

            long duration = System.currentTimeMillis() - startTime;
            result.setDuration(duration);

            log.info("查询完成，返回 {} 行，耗时 {} ms", data.size(), duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            result.setDuration(duration);
            result.setError(e.getMessage());
            log.error("查询失败: {}", description, e);
        }

        return result;
    }

    /**
     * 查询意图
     */
    @Data
    private static class QueryIntent {
        /**
         * 目标表
         */
        String table;

        /**
         * 操作类型（查询、统计、报表等）
         */
        String operation;

        /**
         * WHERE条件
         */
        List<String> conditions = new ArrayList<>();

        /**
         * ORDER BY字段
         */
        String orderBy;

        /**
         * 排序方向（ASC/DESC）
         */
        String orderDirection;

        /**
         * 限制返回行数
         */
        Integer limit;

        /**
         * 分组字段
         */
        List<String> groupBy = new ArrayList<>();

        /**
         * 聚合函数（COUNT, SUM, AVG等）
         */
        List<String> aggregates = new ArrayList<>();
    }

    /**
     * 解析查询意图，识别目标表、操作类型、限制条件等
     *
     * @param description 自然语言描述
     * @return 解析后的查询意图
     */
    private QueryIntent parseQueryIntent(String description) {
        QueryIntent intent = new QueryIntent();
        String lowerDesc = description.toLowerCase();

        // 1. 识别表名
        if (lowerDesc.contains("菜品") || lowerDesc.contains("菜谱") || lowerDesc.contains("菜")) {
            intent.setTable("dish_library");
        } else if (lowerDesc.contains("食材") || lowerDesc.contains("原料")) {
            intent.setTable("food_ingredient");
        } else if (lowerDesc.contains("营养") || lowerDesc.contains("营养素")) {
            intent.setTable("nutrient");
        } else if (lowerDesc.contains("营养标准")) {
            intent.setTable("nutrient_standard");
        } else if (lowerDesc.contains("评分") || lowerDesc.contains("分数")) {
            intent.setTable("cook_book_score_index_detail");
        } else if (lowerDesc.contains("分类") || lowerDesc.contains("类别")) {
            intent.setTable("dish_class");
        } else {
            intent.setTable("dish_library"); // 默认表
        }

        // 2. 识别操作类型
        if (lowerDesc.contains("统计") || lowerDesc.contains("汇总") || lowerDesc.contains("总数") ||
                lowerDesc.contains("数量") || lowerDesc.contains("个数")) {
            intent.setOperation("statistics");
        } else if (lowerDesc.contains("分组") || lowerDesc.contains("按")) {
            intent.setOperation("group");
        } else if (lowerDesc.contains("报表") || lowerDesc.contains("报告")) {
            intent.setOperation("report");
        } else {
            intent.setOperation("query"); // 默认操作
        }

        // 3. 解析LIMIT
        Pattern limitPattern = Pattern.compile("(\\d+)\\s*[条个]");
        Matcher limitMatcher = limitPattern.matcher(description);
        if (limitMatcher.find()) {
            intent.setLimit(Integer.parseInt(limitMatcher.group(1)));
        } else if (lowerDesc.contains("最近")) {
            intent.setLimit(10);
        } else if (lowerDesc.contains("所有") || lowerDesc.contains("全部")) {
            intent.setLimit(null);
        } else {
            intent.setLimit(20); // 默认20条
        }

        // 4. 解析排序
        if (lowerDesc.contains("最新") || lowerDesc.contains("最近")) {
            intent.setOrderBy("update_time");
            intent.setOrderDirection("DESC");
        } else if (lowerDesc.contains("按") && lowerDesc.contains("排序")) {
            // 简单处理：查找"按xxx排序"
            Pattern sortPattern = Pattern.compile("按(\\w+)排序");
            Matcher sortMatcher = sortPattern.matcher(description);
            if (sortMatcher.find()) {
                intent.setOrderBy(sortMatcher.group(1));
                intent.setOrderDirection("ASC");
            }
        }

        return intent;
    }

    /**
     * 根据查询意图生成 SQL 语句
     *
     * @param intent 查询意图
     * @return 生成的 SQL 语句
     */
    private String generateSql(QueryIntent intent) {
        StringBuilder sql = new StringBuilder();

        switch (intent.operation) {
            case "statistics":
                // 统计查询
                sql.append("SELECT COUNT(*) as count");

                if (intent.getGroupBy() != null && !intent.getGroupBy().isEmpty()) {
                    for (String field : intent.getGroupBy()) {
                        sql.append(", ").append(field);
                    }
                    sql.append(", COUNT(*) as group_count");
                }

                sql.append(" FROM ").append(intent.table);

                if (!intent.getConditions().isEmpty()) {
                    sql.append(" WHERE ").append(String.join(" AND ", intent.getConditions()));
                }

                if (intent.getGroupBy() != null && !intent.getGroupBy().isEmpty()) {
                    sql.append(" GROUP BY ").append(String.join(", ", intent.getGroupBy()));
                }

                break;

            case "group":
                // 分组查询
                sql.append("SELECT ");

                if (intent.getGroupBy() != null && !intent.getGroupBy().isEmpty()) {
                    for (String field : intent.getGroupBy()) {
                        sql.append(field).append(", ");
                    }
                }

                sql.append("COUNT(*) as count FROM ").append(intent.table);

                if (!intent.getConditions().isEmpty()) {
                    sql.append(" WHERE ").append(String.join(" AND ", intent.getConditions()));
                }

                if (intent.getGroupBy() != null && !intent.getGroupBy().isEmpty()) {
                    sql.append(" GROUP BY ").append(String.join(", ", intent.getGroupBy()));
                }

                if (intent.getOrderBy() != null) {
                    sql.append(" ORDER BY ").append(intent.getOrderBy());
                    if (intent.getOrderDirection() != null) {
                        sql.append(" ").append(intent.getOrderDirection());
                    }
                }

                break;

            case "report":
            default:
                // 普通查询/报表查询
                sql.append("SELECT * FROM ").append(intent.table);

                if (!intent.getConditions().isEmpty()) {
                    sql.append(" WHERE ").append(String.join(" AND ", intent.getConditions()));
                }

                if (intent.getOrderBy() != null) {
                    sql.append(" ORDER BY ").append(intent.getOrderBy());
                    if (intent.getOrderDirection() != null) {
                        sql.append(" ").append(intent.getOrderDirection());
                    }
                }

                if (intent.getLimit() != null) {
                    sql.append(" LIMIT ").append(intent.getLimit());
                }

                break;
        }

        return sql.toString();
    }

    /**
     * 通过 MCP 数据库服务执行 SQL 查询
     *
     * @param sql SQL 语句
     * @return 查询结果数据列表
     */
    private List<Map<String, Object>> executeQuery(String sql) {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            String response = callMcpDatabase(sql);

            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    Map<String, Object> item = parseRow(row);
                    result.add(item);
                }
            }

        } catch (Exception e) {
            log.error("执行SQL失败: {}", sql, e);
        }

        return result;
    }

    /**
     * 解析数据行字符串为键值对 Map
     *
     * @param row 数据行字符串（key=value 格式）
     * @return 解析后的键值对 Map
     */
    private Map<String, Object> parseRow(String row) {
        Map<String, Object> item = new HashMap<>();

        // 解析键值对：key=value
        Pattern kvPattern = Pattern.compile("(\\w+)=(\\S+)");
        Matcher matcher = kvPattern.matcher(row);

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);

            // 尝试转换为数字
            try {
                if (value.contains(".")) {
                    item.put(key, Double.parseDouble(value));
                } else {
                    item.put(key, Long.parseLong(value));
                }
            } catch (NumberFormatException e) {
                item.put(key, value);
            }
        }

        return item;
    }

    /**
     * 获取指定表的结构信息
     *
     * @param tableName 表名
     * @return 表结构信息
     */
    private QueryResult.TableSchemaInfo getTableSchema(String tableName) {
        QueryResult.TableSchemaInfo schema = new QueryResult.TableSchemaInfo();

        try {
            String sql = "DESCRIBE " + tableName;
            String response = callMcpDatabase(sql);

            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                String[] lines = text.split("\n");

                for (String line : lines) {
                    line = line.trim();
                    if (!line.startsWith("Field") && !line.startsWith("---") && !line.isEmpty()) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            String columnName = parts[0];
                            String columnType = parts[1];
                            schema.getColumns().add(columnName);
                            schema.getColumnTypes().put(columnName, columnType);
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("获取表结构失败: {}", tableName, e);
        }

        return schema;
    }

    /**
     * 调用 MCP 数据库服务执行 SQL
     *
     * @param sql SQL 语句
     * @return MCP 服务响应体，调用失败返回 null
     */
    private String callMcpDatabase(String sql) {
        try {
            String url = String.format("http://%s:%d/mcp", this.mcpDbHost, this.mcpDbPort);

            Map<String, Object> request = new HashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", System.currentTimeMillis());
            request.put("method", "tools/call");
            request.put("params", Map.of(
                    "name", "query_db",
                    "arguments", Map.of(
                            "sql", sql,
                            "limit", 1000
                    )
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            var response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }

        } catch (Exception e) {
            log.error("调用MCP数据库失败", e);
        }

        return null;
    }

    /**
     * 生成数据列表（表格格式）
     *
     * @param result 查询结果
     * @return 格式化的表格字符串
     */
    public String formatDataList(QueryResult result) {
        if (result.getData().isEmpty()) {
            return "无数据";
        }

        StringBuilder sb = new StringBuilder();
        QueryResult.TableSchemaInfo schema = result.getSchema();

        // 表头
        sb.append("查询结果 - ").append(result.getTableName()).append("\n");
        sb.append("SQL: ").append(result.getSql()).append("\n");
        sb.append("返回行数: ").append(result.getRowCount()).append("\n");
        sb.append("耗时: ").append(result.getDuration()).append(" ms\n");
        sb.append("-".repeat(80)).append("\n");

        if (!schema.getColumns().isEmpty()) {
            sb.append("  ");
            for (String column : schema.getColumns()) {
                String colStr = column.length() > 15 ? column.substring(0, 15) : column;
                sb.append(String.format("%-16s ", colStr));
            }
            sb.append("\n");
            sb.append("  ").append("-".repeat(16 * schema.getColumns().size())).append("\n");
        }

        // 数据行
        for (Map<String, Object> row : result.getData()) {
            sb.append("  ");
            for (String column : schema.getColumns()) {
                Object value = row.get(column);
                String valueStr = value == null ? "NULL" : value.toString();
                if (valueStr.length() > 14) {
                    valueStr = valueStr.substring(0, 14) + "...";
                }
                sb.append(String.format("%-16s ", valueStr));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 生成统计报表，包含基本统计、字段信息和数据预览
     *
     * @param result 查询结果
     * @return 格式化的统计报表字符串
     */
    public String generateStatisticsReport(QueryResult result) {
        StringBuilder report = new StringBuilder();

        report.append("=" .repeat(50)).append("\n");
        report.append("数据统计报告\n");
        report.append("表名: ").append(result.getTableName()).append("\n");
        report.append("查询时间: ").append(new Date()).append("\n");
        report.append("-".repeat(50)).append("\n\n");

        // 基本统计
        report.append("【基本统计】\n");
        report.append("返回行数: ").append(result.getRowCount()).append("\n");
        report.append("查询耗时: ").append(result.getDuration()).append(" ms\n");
        report.append("SQL语句: ").append(result.getSql()).append("\n\n");

        // 字段统计
        report.append("【字段统计】\n");
        QueryResult.TableSchemaInfo schema = result.getSchema();
        if (schema != null && !schema.getColumns().isEmpty()) {
            report.append("字段数量: ").append(schema.getColumns().size()).append("\n");
            report.append("字段列表: ").append(String.join(", ", schema.getColumns())).append("\n\n");
        }

        // 数据采样（前5条）
        report.append("【数据预览】\n");
        int sampleSize = Math.min(5, result.getData().size());
        for (int i = 0; i < sampleSize; i++) {
            Map<String, Object> row = result.getData().get(i);
            report.append(String.format("  记录%d: %s\n", i + 1, row));
        }

        if (result.getRowCount() > 5) {
            report.append("  ... (还有 ").append(result.getRowCount() - 5).append(" 条记录)\n");
        }

        return report.toString();
    }
}
