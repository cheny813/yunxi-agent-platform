package io.yunxi.platform.shared.util.database;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 数据探索器 - 查看表数据、索引、统计信息
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
public class DataExplorer {

    private final String mcpHost;
    private final int mcpPort;
    private final RestTemplate restTemplate;

    public DataExplorer(String mcpHost, int mcpPort, RestTemplate restTemplate) {
        this.mcpHost = mcpHost;
        this.mcpPort = mcpPort;
        this.restTemplate = restTemplate;
    }

    /**
     * 查看所有表
     */
    public List<String> listTables() {
        List<String> tables = new ArrayList<>();

        try {
            String response = callMcpTool("list_tables", Map.of());
            // 解析响应提取表名
            // ... 实现解析逻辑

        } catch (Exception e) {
            log.error("获取表列表失败", e);
        }

        return tables;
    }

    /**
     * 查看表索引
     */
    public List<TableIndex> showIndexes(String tableName) {
        List<TableIndex> indexes = new ArrayList<>();

        try {
            String sql = String.format("SHOW INDEX FROM %s", tableName);
            String response = callMcpTool("query_db", Map.of(
                    "sql", sql,
                    "limit", 100
            ));

            // 解析响应提取索引信息
            // ... 实现解析逻辑

        } catch (Exception e) {
            log.error("获取表索引失败: {}", tableName, e);
        }

        return indexes;
    }

    /**
     * 查看表数据样本
     */
    public List<Map<String, Object>> sampleData(String tableName, int limit) {
        List<Map<String, Object>> data = new ArrayList<>();

        try {
            String sql = String.format("SELECT * FROM %s LIMIT %d", tableName, limit);
            String response = callMcpTool("query_db", Map.of(
                    "sql", sql,
                    "limit", limit
            ));

            // 解析响应提取数据
            // ... 实现解析逻辑

        } catch (Exception e) {
            log.error("获取表数据样本失败: {}", tableName, e);
        }

        return data;
    }

    /**
     * 获取表统计信息
     */
    public TableStatistics getStatistics(String tableName) {
        TableStatistics stats = new TableStatistics();
        stats.setTableName(tableName);

        try {
            String sql = String.format(
                    "SELECT TABLE_NAME, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, " +
                            "DATA_LENGTH + INDEX_LENGTH as TOTAL_LENGTH, " +
                            "AVG_ROW_LENGTH, AUTO_INCREMENT, CREATE_TIME, UPDATE_TIME, TABLE_COLLATION " +
                            "FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '%s'",
                    tableName
            );

            String response = callMcpTool("query_db", Map.of("sql", sql, "limit", 10));

            // 解析响应填充统计信息
            // ... 实现解析逻辑

        } catch (Exception e) {
            log.error("获取表统计信息失败: {}", tableName, e);
        }

        return stats;
    }

    /**
     * 格式化数据样本
     */
    public static String formatSamples(List<Map<String, Object>> samples) {
        if (samples.isEmpty()) {
            return "  (无数据)";
        }

        StringBuilder sb = new StringBuilder();

        // 表头
        if (!samples.isEmpty()) {
            Map<String, Object> firstRow = samples.get(0);
            sb.append("  ");
            for (String key : firstRow.keySet()) {
                sb.append(String.format("%-20s ", key));
            }
            sb.append("\n");
            sb.append("  ").append("-".repeat(20 * firstRow.size())).append("\n");
        }

        // 数据行
        for (Map<String, Object> row : samples) {
            sb.append("  ");
            for (Object value : row.values()) {
                String strValue = value == null ? "NULL" : value.toString();
                if (strValue.length() > 18) {
                    strValue = strValue.substring(0, 17) + "...";
                }
                sb.append(String.format("%-20s ", strValue));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 调用MCP工具
     */
    private String callMcpTool(String toolName, Map<String, Object> arguments) {
        try {
            String url = String.format("http://%s:%d/mcp", mcpHost, mcpPort);

            Map<String, Object> request = new HashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", System.currentTimeMillis());
            request.put("method", "tools/call");
            request.put("params", Map.of(
                    "name", toolName,
                    "arguments", arguments
            ));

            org.springframework.http.HttpEntity<Map<String, Object>> entity =
                    new org.springframework.http.HttpEntity<>(request);

            org.springframework.http.ResponseEntity<String> response =
                    restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }

        } catch (Exception e) {
            log.error("调用MCP工具失败: {}", toolName, e);
        }

        return null;
    }
}
