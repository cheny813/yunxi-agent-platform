package io.yunxi.platform.shared.util.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schema检查器 - 获取和验证表结构
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
public class SchemaInspector {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, TableSchema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    private final String mcpHost;
    private final int mcpPort;
    private final RestTemplate restTemplate;

    public SchemaInspector(String mcpHost, int mcpPort, RestTemplate restTemplate) {
        this.mcpHost = mcpHost;
        this.mcpPort = mcpPort;
        this.restTemplate = restTemplate;
    }

    /**
     * 查看表结构
     */
    public TableSchema describeTable(String tableName) {
        // 检查缓存
        String cacheKey = tableName.toLowerCase();
        if (SCHEMA_CACHE.containsKey(cacheKey)) {
            return SCHEMA_CACHE.get(cacheKey);
        }

        try {
            String response = callMcpTool("describe_table", Map.of("table_name", tableName));
            TableSchema schema = parseDescribeResponse(response);

            if (schema != null) {
                schema.setTableName(tableName);
                SCHEMA_CACHE.put(cacheKey, schema);
                log.info("表结构已缓存: {}", tableName);
            }

            return schema;
        } catch (Exception e) {
            log.error("获取表结构失败: {}", tableName, e);
            return null;
        }
    }

    /**
     * 批量查看表结构
     */
    public Map<String, TableSchema> describeTables(List<String> tableNames) {
        Map<String, TableSchema> result = new HashMap<>();

        for (String tableName : tableNames) {
            TableSchema schema = describeTable(tableName);
            if (schema != null) {
                result.put(tableName, schema);
            }
        }

        return result;
    }

    /**
     * 格式化表结构输出
     */
    public String formatSchema(TableSchema schema) {
        if (schema == null) {
            return "表结构信息为空";
        }

        StringBuilder report = new StringBuilder();
        report.append("表名: ").append(schema.getTableName()).append("\n");

        if (schema.getEngine() != null) {
            report.append("引擎: ").append(schema.getEngine()).append("\n");
        }

        if (schema.getCharset() != null) {
            report.append("字符集: ").append(schema.getCharset()).append("\n");
        }

        if (schema.getComment() != null) {
            report.append("注释: ").append(schema.getComment()).append("\n");
        }

        report.append("\n字段列表 (").append(schema.getColumns().size()).append("):\n");
        report.append("-".repeat(80)).append("\n");

        for (String columnName : schema.getColumns()) {
            ColumnInfo column = schema.getColumn(columnName);
            if (column != null) {
                String pkMarker = schema.isPrimaryKey(columnName) ? " 🔑" : "";
                String uniqueMarker = column.isUnique() ? " 🔑" : "";
                String nullableMarker = column.getNullable() ? " NULL" : " NOT NULL";
                String defaultStr = column.getDefaultValue() != null ?
                        " DEFAULT " + column.getDefaultValue() : "";
                String commentStr = column.getComment() != null ?
                        " # " + column.getComment() : "";

                report.append(String.format("  %-25s %-20s%s%s%s%s%s\n",
                        columnName,
                        column.getType(),
                        pkMarker,
                        uniqueMarker,
                        nullableMarker,
                        defaultStr,
                        commentStr
                ));
            }
        }

        if (!schema.getPrimaryKeys().isEmpty()) {
            report.append("\n主键: ").append(schema.getPrimaryKeys()).append("\n");
        }

        return report.toString();
    }

    /**
     * 解析describe_table响应
     */
    private TableSchema parseDescribeResponse(String response) {
        TableSchema schema = new TableSchema();

        try {
            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode result = jsonNode.get("result");

            if (result != null && result.has("content")) {
                JsonNode content = result.get("content");
                if (content.isArray() && content.size() > 0) {
                    JsonNode textNode = content.get(0).get("text");
                    if (textNode != null) {
                        String text = textNode.asText();
                        parseDescribeText(text, schema);
                    }
                }
            }

        } catch (Exception e) {
            log.error("解析表结构响应失败", e);
        }

        return schema.getColumns().isEmpty() ? null : schema;
    }

    /**
     * 解析DESCRIBE命令的文本输出
     */
    private void parseDescribeText(String text, TableSchema schema) {
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
                ColumnInfo column = new ColumnInfo();
                column.setName(parts[0]);
                column.setType(parts[1]);

                // 解析其他属性
                for (int i = 2; i < parts.length; i++) {
                    String part = parts[i].toUpperCase();

                    if ("NO".equals(part)) {
                        column.setNullable(false);
                    } else if ("YES".equals(part)) {
                        column.setNullable(true);
                    } else if ("PRI".equals(part)) {
                        column.setPrimaryKey(true);
                        schema.getPrimaryKeys().add(column.getName().toLowerCase());
                    } else if ("UNI".equals(part)) {
                        column.setUnique(true);
                    } else if ("MUL".equals(part)) {
                        // 普通索引
                    } else if (part.startsWith("DEFAULT") && i + 1 < parts.length) {
                        column.setDefaultValue(parts[++i]);
                    } else if ("AUTO_INCREMENT".equalsIgnoreCase(part)) {
                        column.setAutoIncrement(true);
                    }
                }

                String lowerName = column.getName().toLowerCase();
                schema.getColumns().add(lowerName);
                schema.getColumnDetails().put(lowerName, column);
            }
        }
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        SCHEMA_CACHE.clear();
        log.info("Schema缓存已清除");
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

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

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
