package io.yunxi.platform.shared.util.database;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL分析器 - 分析SQL性能和优化建议
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
public class SqlAnalyzer {

    /**
     * 分析SQL性能
     */
    public static SqlPerformanceReport analyze(String sql, String mcpHost, int mcpPort, RestTemplate restTemplate) {
        SqlPerformanceReport report = new SqlPerformanceReport();
        report.setSql(sql);

        try {
            // 确定查询类型
            report.setQueryType(determineQueryType(sql));

            // 执行EXPLAIN
            String explainResult = explain(sql, mcpHost, mcpPort, restTemplate);
            report.setExplainResult(explainResult);

            // 解析EXPLAIN结果
            parseExplainResult(explainResult, report);

            // 生成优化建议
            List<String> optimizations = suggestOptimizations(sql);
            report.getOptimizations().addAll(optimizations);

            // 评估性能等级
            evaluatePerformance(report);

        } catch (Exception e) {
            log.error("分析SQL性能失败", e);
            report.addWarning("分析过程发生异常: " + e.getMessage());
        }

        return report;
    }

    /**
     * 执行EXPLAIN
     */
    public static String explain(String sql, String mcpHost, int mcpPort, RestTemplate restTemplate) {
        try {
            String explainSql = "EXPLAIN " + sql;

            Map<String, Object> arguments = Map.of(
                    "sql", explainSql,
                    "limit", 100
            );

            String url = String.format("http://%s:%d/mcp", mcpHost, mcpPort);
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "id", System.currentTimeMillis(),
                    "method", "tools/call",
                    "params", Map.of(
                            "name", "query_db",
                            "arguments", arguments
                    )
            );

            org.springframework.http.HttpEntity<Map<String, Object>> entity =
                    new org.springframework.http.HttpEntity<>(request);

            org.springframework.http.ResponseEntity<String> response =
                    restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }

        } catch (Exception e) {
            log.error("执行EXPLAIN失败", e);
        }

        return null;
    }

    /**
     * 解析EXPLAIN结果
     */
    private static void parseExplainResult(String explainResult, SqlPerformanceReport report) {
        if (explainResult == null) {
            return;
        }

        try {
            // 解析EXPLAIN输出
            String[] lines = explainResult.split("\n");

            for (String line : lines) {
                line = line.trim();

                if (line.startsWith("rows")) {
                    // 提取扫描行数
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1) {
                        try {
                            report.setRowsExamined(Long.parseLong(parts[1]));
                        } catch (NumberFormatException e) {
                            log.debug("解析rows字段失败", e);
                        }
                    }
                }

                if (line.toLowerCase().contains("using filesort")) {
                    report.setUsingFilesort(true);
                    report.addWarning("使用了文件排序（Using filesort），可能影响性能");
                }

                if (line.toLowerCase().contains("using temporary")) {
                    report.setUsingTempTable(true);
                    report.addWarning("使用了临时表（Using temporary），可能影响性能");
                }

                if (line.toLowerCase().contains("using index")) {
                    report.setIndexUsed(true);
                    // 提取索引名
                    String[] parts = line.split("\\s+");
                    for (String part : parts) {
                        if (!part.equals("using") && !part.equals("index")) {
                            report.setIndexName(part);
                            break;
                        }
                    }
                }

                if (line.toLowerCase().contains("type: all")) {
                    report.addWarning("全表扫描（type: ALL），建议添加索引");
                    if (report.getPerformanceLevel() == SqlPerformanceReport.PerformanceLevel.EXCELLENT) {
                        report.setPerformanceLevel(SqlPerformanceReport.PerformanceLevel.FAIR);
                    }
                }
            }

        } catch (Exception e) {
            log.error("解析EXPLAIN结果失败", e);
        }
    }

    /**
     * 确定查询类型
     */
    private static String determineQueryType(String sql) {
        String trimmedSql = sql.trim().toUpperCase();

        if (trimmedSql.startsWith("SELECT")) {
            return "SELECT";
        } else if (trimmedSql.startsWith("INSERT")) {
            return "INSERT";
        } else if (trimmedSql.startsWith("UPDATE")) {
            return "UPDATE";
        } else if (trimmedSql.startsWith("DELETE")) {
            return "DELETE";
        } else {
            return "UNKNOWN";
        }
    }

    /**
     * 评估性能等级
     */
    private static void evaluatePerformance(SqlPerformanceReport report) {
        int score = 100;

        // 扣分项
        if (!report.isIndexUsed()) {
            score -= 30;
        }
        if (report.isUsingTempTable()) {
            score -= 20;
        }
        if (report.isUsingFilesort()) {
            score -= 15;
        }
        if (report.getRowsExamined() != null && report.getRowsExamined() > 10000) {
            score -= 10;
        }

        // 确定等级
        if (score >= 80) {
            report.setPerformanceLevel(SqlPerformanceReport.PerformanceLevel.EXCELLENT);
        } else if (score >= 60) {
            report.setPerformanceLevel(SqlPerformanceReport.PerformanceLevel.GOOD);
        } else if (score >= 40) {
            report.setPerformanceLevel(SqlPerformanceReport.PerformanceLevel.FAIR);
        } else {
            report.setPerformanceLevel(SqlPerformanceReport.PerformanceLevel.POOR);
        }
    }

    /**
     * 生成优化建议
     */
    public static List<String> suggestOptimizations(String sql) {
        List<String> suggestions = new ArrayList<>();

        try {
            String normalizedSql = sql.toLowerCase();

            // 建议使用LIMIT
            if (normalizedSql.contains("select") && !normalizedSql.contains("limit")) {
                suggestions.add("考虑添加LIMIT子句限制返回行数，提高性能");
            }

            // 建议避免SELECT *
            if (normalizedSql.contains("select *")) {
                suggestions.add("避免使用SELECT *，只查询需要的字段可以减少I/O和网络传输");
            }

            // 建议WHERE条件
            if (normalizedSql.contains("select") && !normalizedSql.contains("where")) {
                suggestions.add("考虑添加WHERE条件过滤数据，减少扫描行数");
            }

            // 建议索引
            if (normalizedSql.contains("where") && normalizedSql.contains("=")) {
                suggestions.add("确保WHERE条件中的字段有适当的索引");
            }

            // 建议JOIN优化
            if (normalizedSql.contains("join")) {
                suggestions.add("确保JOIN字段上有索引，并且使用相同的数据类型");
            }

        } catch (Exception e) {
            log.error("生成优化建议失败", e);
        }

        return suggestions;
    }
}
