package io.yunxi.platform.shared.util.database;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 关系映射器 - 分析表之间的关联关系
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
public class RelationshipMapper {

    private final String mcpHost;
    private final int mcpPort;
    private final RestTemplate restTemplate;
    private final SchemaInspector inspector;

    public RelationshipMapper(String mcpHost, int mcpPort, RestTemplate restTemplate) {
        this.mcpHost = mcpHost;
        this.mcpPort = mcpPort;
        this.restTemplate = restTemplate;
        this.inspector = new SchemaInspector(mcpHost, mcpPort, restTemplate);
    }

    /**
     * 获取表的关联关系
     */
    public List<TableRelationship> getRelationships(String tableName) {
        List<TableRelationship> relationships = new ArrayList<>();

        try {
            // 1. 查询外键关系
            relationships.addAll(getForeignKeys(tableName));

            // 2. 基于字段名推断关联关系
            relationships.addAll(inferRelationships(tableName));

        } catch (Exception e) {
            log.error("获取表关系失败: {}", tableName, e);
        }

        return relationships;
    }

    /**
     * 查询外键关系
     */
    private List<TableRelationship> getForeignKeys(String tableName) {
        List<TableRelationship> relationships = new ArrayList<>();

        try {
            String sql = String.format(
                    "SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, " +
                            "REFERENCED_COLUMN_NAME " +
                            "FROM information_schema.KEY_COLUMN_USAGE " +
                            "WHERE TABLE_SCHEMA = DATABASE() " +
                            "AND TABLE_NAME = '%s' " +
                            "AND REFERENCED_TABLE_NAME IS NOT NULL",
                    tableName
            );

            String response = callMcpTool("query_db", Map.of("sql", sql, "limit", 100));

            // 解析响应提取外键关系
            // ... 实现解析逻辑

        } catch (Exception e) {
            log.error("查询外键失败: {}", tableName, e);
        }

        return relationships;
    }

    /**
     * 基于字段名推断关联关系
     */
    private List<TableRelationship> inferRelationships(String tableName) {
        List<TableRelationship> relationships = new ArrayList<>();

        try {
            TableSchema schema = inspector.describeTable(tableName);
            if (schema == null) {
                return relationships;
            }

            List<String> allTables = new DataExplorer(mcpHost, mcpPort, restTemplate).listTables();

            // 遍历每个字段，查找可能的关联
            for (String column : schema.getColumns()) {
                // 匹配模式：字段名以 _id 结尾，可能是外键
                if (column.endsWith("_id")) {
                    String potentialTable = column.substring(0, column.length() - 3); // 去掉 _id

                    // 检查是否存在对应的表
                    for (String table : allTables) {
                        if (table.equalsIgnoreCase(potentialTable)) {
                            TableRelationship relationship = new TableRelationship(
                                    tableName,
                                    column,
                                    table,
                                    "id",
                                    TableRelationship.RelationType.POTENTIAL
                            );
                            relationship.setConfidence(0.7);
                            relationships.add(relationship);
                            break;
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("推断表关系失败: {}", tableName, e);
        }

        return relationships;
    }

    /**
     * 可视化表关系（Mermaid格式）
     */
    public String visualize(String tableName) {
        List<TableRelationship> relationships = getRelationships(tableName);

        StringBuilder mermaid = new StringBuilder();
        mermaid.append("erDiagram\n");

        Set<String> tables = new HashSet<>();
        tables.add(tableName);

        for (TableRelationship rel : relationships) {
            tables.add(rel.getFromTable());
            tables.add(rel.getToTable());

            String relation = getMermaidRelation(rel);
            mermaid.append(String.format("  %s %s %s : \"%s\"\n",
                    rel.getFromTable(),
                    relation,
                    rel.getToTable(),
                    rel.getFromColumn()
            ));
        }

        return mermaid.toString();
    }

    /**
     * 批量可视化表关系
     */
    public String visualizeBatch(List<String> tableNames) {
        Set<String> allTables = new HashSet<>(tableNames);

        // 收集所有关联表
        for (String tableName : tableNames) {
            List<TableRelationship> relationships = getRelationships(tableName);
            for (TableRelationship rel : relationships) {
                allTables.add(rel.getFromTable());
                allTables.add(rel.getToTable());
            }
        }

        // 生成Mermaid图
        StringBuilder mermaid = new StringBuilder();
        mermaid.append("erDiagram\n");

        for (String tableName : tableNames) {
            List<TableRelationship> relationships = getRelationships(tableName);

            for (TableRelationship rel : relationships) {
                String relation = getMermaidRelation(rel);
                mermaid.append(String.format("  %s %s %s : \"%s\"\n",
                        rel.getFromTable(),
                        relation,
                        rel.getToTable(),
                        rel.getFromColumn()
                ));
            }
        }

        return mermaid.toString();
    }

    /**
     * 格式化关系输出
     */
    public String formatRelationships(List<TableRelationship> relationships) {
        if (relationships.isEmpty()) {
            return "  无关联关系";
        }

        StringBuilder sb = new StringBuilder();

        for (TableRelationship rel : relationships) {
            sb.append("  ").append(rel.getRelationshipDescription()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 获取Mermaid关系符号
     */
    private String getMermaidRelation(TableRelationship rel) {
        switch (rel.getType()) {
            case ONE_TO_ONE:
                return "||--||";
            case ONE_TO_MANY:
                return "||--|{";
            case MANY_TO_MANY:
                return "}|--|{";
            case FOREIGN_KEY:
                return "}o--||";
            default:
                return "o--o";
        }
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
