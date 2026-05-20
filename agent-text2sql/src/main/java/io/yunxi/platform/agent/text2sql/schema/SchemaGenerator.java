package io.yunxi.platform.agent.text2sql.schema;

import io.yunxi.platform.spi.text2sql.DatabaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Schema Generator
 * <p>
 * 从 MCP 数据库生成数据库 Schema
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
public class SchemaGenerator {

    private static final Logger log = LoggerFactory.getLogger(SchemaGenerator.class);

    @Autowired(required = false)
    private DatabaseClient databaseClient;

    /**
     * 生成数据库 Schema
     *
     * @param databaseId 数据库 ID
     * @return 数据库 Schema
     */
    @Cacheable(value = "schemaCache", key = "#databaseId", unless = "#result == null")
    public DatabaseSchema generateSchema(String databaseId) {
        try {
            log.info("开始生成 Schema: databaseId={}", databaseId);

            DatabaseSchema schema = new DatabaseSchema();
            schema.setDatabaseId(databaseId);

            // 1. 获取所有表
            List<String> tables = listTables(databaseId);
            schema.setTables(new ArrayList<>());

            // 2. 为每个表生成 Schema
            for (String tableName : tables) {
                TableSchema tableSchema = describeTable(databaseId, tableName);
                if (tableSchema != null) {
                    schema.getTables().add(tableSchema);
                }
            }

            // 3. 推断外键关系
            inferForeignKeys(schema);

            log.info("Schema 生成完成: databaseId={}, tables={}", databaseId, schema.getTables().size());
            return schema;

        } catch (Exception e) {
            log.error("生成 Schema 失败: databaseId={}", databaseId, e);
            return null;
        }
    }

    /**
     * 列出所有表（公开方法）
     */
    public List<String> listTablesPublic(String databaseId) {
        return listTables(databaseId);
    }

    /**
     * 描述表结构（公开方法）
     */
    public TableSchema describeTablePublic(String databaseId, String tableName) {
        return describeTable(databaseId, tableName);
    }

    /**
     * 列出所有表
     */
    private List<String> listTables(String databaseId) {
        List<String> tables = new ArrayList<>();
        try {
            if (databaseClient == null || !databaseClient.isAvailable()) {
                log.warn("DatabaseClient 未配置或不可用");
                return tables;
            }
            tables = databaseClient.listTables();
        } catch (Exception e) {
            log.error("列出表失败: databaseId={}", databaseId, e);
        }
        return tables;
    }

    /**
     * 描述表结构
     */
    private TableSchema describeTable(String databaseId, String tableName) {
        try {
            if (databaseClient == null || !databaseClient.isAvailable()) {
                log.warn("DatabaseClient 未配置或不可用");
                return null;
            }
            DatabaseClient.TableSchema spiSchema = databaseClient.describeTable(tableName);
            if (spiSchema == null) {
                return null;
            }

            TableSchema tableSchema = new TableSchema();
            tableSchema.setTableName(spiSchema.getTableName());
            tableSchema.setColumns(new ArrayList<>());

            // 转换 SPI 列信息为本地列信息
            if (spiSchema.getColumns() != null) {
                for (DatabaseClient.ColumnInfo columnInfo : spiSchema.getColumns()) {
                    ColumnSchema columnSchema = new ColumnSchema();
                    columnSchema.setColumnName(columnInfo.getName());
                    columnSchema.setDataType(columnInfo.getType());
                    columnSchema.setNullable(columnInfo.isNullable());
                    // SPI 接口不包含主键、唯一、自增信息，使用默认值
                    columnSchema.setPrimaryKey(false);
                    columnSchema.setUnique(false);
                    columnSchema.setAutoIncrement(false);

                    tableSchema.getColumns().add(columnSchema);
                }
            }

            return tableSchema;

        } catch (Exception e) {
            log.error("描述表失败: databaseId={}, table={}", databaseId, tableName, e);
            return null;
        }
    }

    /**
     * 推断外键关系
     */
    private void inferForeignKeys(DatabaseSchema schema) {
        Map<String, TableSchema> tableMap = new HashMap<>();
        for (TableSchema table : schema.getTables()) {
            tableMap.put(table.getTableName(), table);
        }

        List<ForeignKey> foreignKeys = new ArrayList<>();

        for (TableSchema table : schema.getTables()) {
            for (ColumnSchema column : table.getColumns()) {
                // 检查是否是外键（列名以 _id 或 Id 结尾）
                String columnName = column.getColumnName();
                if (columnName.endsWith("_id") || columnName.endsWith("Id") || columnName.endsWith("ID")) {
                    // 推断引用的表名
                    String referencedTableName = columnName.replaceAll("_id$", "").replaceAll("Id$", "").replaceAll("ID$", "").toLowerCase();

                    if (tableMap.containsKey(referencedTableName)) {
                        ForeignKey fk = new ForeignKey();
                        fk.setColumnName(columnName);
                        fk.setReferencedTable(referencedTableName);
                        fk.setReferencedColumn("id");  // 假设主键是 id
                        fk.setConfidence(0.8);  // 推断的置信度

                        foreignKeys.add(fk);
                    }
                }
            }
        }

        schema.setForeignKeys(foreignKeys);
    }

    // ==================== 数据模型 ====================

    public static class DatabaseSchema {
        private String databaseId;
        private List<TableSchema> tables;
        private List<ForeignKey> foreignKeys;

        public String toLLMPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("Database Schema:\n\n");

            for (TableSchema table : tables) {
                sb.append("Table: ").append(table.getTableName()).append("\n");
                for (ColumnSchema column : table.getColumns()) {
                    sb.append("  - ").append(column.getColumnName())
                      .append(" (").append(column.getDataType()).append(")");
                    if (column.isPrimaryKey()) {
                        sb.append(" [PK]");
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            if (foreignKeys != null && !foreignKeys.isEmpty()) {
                sb.append("Foreign Keys:\n");
                for (ForeignKey fk : foreignKeys) {
                    sb.append("  - ").append(fk.getColumnName())
                      .append(" -> ").append(fk.getReferencedTable())
                      .append(".").append(fk.getReferencedColumn()).append("\n");
                }
            }

            return sb.toString();
        }

        // Getters and Setters
        public String getDatabaseId() { return databaseId; }
        public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
        public List<TableSchema> getTables() { return tables; }
        public void setTables(List<TableSchema> tables) { this.tables = tables; }
        public List<ForeignKey> getForeignKeys() { return foreignKeys; }
        public void setForeignKeys(List<ForeignKey> foreignKeys) { this.foreignKeys = foreignKeys; }
    }

    public static class TableSchema {
        private String tableName;
        private List<ColumnSchema> columns;

        // Getters and Setters
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public List<ColumnSchema> getColumns() { return columns; }
        public void setColumns(List<ColumnSchema> columns) { this.columns = columns; }
    }

    public static class ColumnSchema {
        private String columnName;
        private String dataType;
        private boolean primaryKey;
        private boolean unique;
        private boolean nullable;
        private boolean autoIncrement;

        // Getters and Setters
        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public String getDataType() { return dataType; }
        public void setDataType(String dataType) { this.dataType = dataType; }
        public boolean isPrimaryKey() { return primaryKey; }
        public void setPrimaryKey(boolean primaryKey) { this.primaryKey = primaryKey; }
        public boolean isUnique() { return unique; }
        public void setUnique(boolean unique) { this.unique = unique; }
        public boolean isNullable() { return nullable; }
        public void setNullable(boolean nullable) { this.nullable = nullable; }
        public boolean isAutoIncrement() { return autoIncrement; }
        public void setAutoIncrement(boolean autoIncrement) { this.autoIncrement = autoIncrement; }
    }

    public static class ForeignKey {
        private String columnName;
        private String referencedTable;
        private String referencedColumn;
        private double confidence;

        // Getters and Setters
        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public String getReferencedTable() { return referencedTable; }
        public void setReferencedTable(String referencedTable) { this.referencedTable = referencedTable; }
        public String getReferencedColumn() { return referencedColumn; }
        public void setReferencedColumn(String referencedColumn) { this.referencedColumn = referencedColumn; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
    }
}
