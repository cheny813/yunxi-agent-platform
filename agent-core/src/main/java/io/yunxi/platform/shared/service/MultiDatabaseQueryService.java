package io.yunxi.platform.shared.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.shared.config.MultiDatabaseConfig;
import io.yunxi.platform.shared.util.TextParserUtil;
import io.yunxi.platform.shared.util.database.DatabaseToolkit;
import io.yunxi.platform.shared.util.mcp.McpDatabaseClient;
import io.yunxi.platform.shared.util.nlq.NaturalLanguageQueryParser;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 多数据库查询服务
 * <p>
 * 通过单一 MCP 数据库服务器（mcp-database）查询多个业务数据库。
 * 数据库路由通过 db_id 参数实现，连接信息由 mcp-database 统一管理。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
public class MultiDatabaseQueryService {

    @Autowired
    private MultiDatabaseConfig multiDatabaseConfig;

    @Autowired
    private DatabaseToolkit databaseToolkit;

    /** 单一 MCP 客户端（所有数据库通过同一个 MCP 服务器路由） */
    private McpDatabaseClient mcpClient;
    private final Map<String, NaturalLanguageQueryParser> parserCache = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${mcp.database.host:localhost}")
    private String mcpHost;

    @Value("${mcp.database.port:40101}")
    private int mcpPort;

    /**
     * 获取 MCP 客户端（延迟初始化，单例）
     */
    private McpDatabaseClient getMcpClient() {
        if (mcpClient == null) {
            mcpClient = new McpDatabaseClient(mcpHost, mcpPort);
        }
        return mcpClient;
    }

    /**
     * 根据自然语言描述查询数据
     *
     * @param databaseId  数据库标识符（如 nutrition, finance, foodsafety）
     * @param description 自然语言描述
     * @return 查询结果
     */
    public QueryResult query(String databaseId, String description) {
        QueryResult result = new QueryResult();
        long startTime = System.currentTimeMillis();

        try {
            log.info("查询请求: database={}, description={}", databaseId, description);

            // 1. 获取数据库配置
            MultiDatabaseConfig.DatabaseInfo dbInfo = multiDatabaseConfig.getDatabaseInfo(databaseId);
            if (dbInfo == null) {
                result.setSuccess(false);
                result.setError("数据库配置不存在: " + databaseId);
                return result;
            }

            // 2. 获取 MCP 客户端（单一实例，通过 db_id 路由）
            McpDatabaseClient client = getMcpClient();

            // 3. 解析查询意图
            NaturalLanguageQueryParser parser = getParser(databaseId);
            NaturalLanguageQueryParser.QueryIntent intent = parser.parse(description);

            if (intent.getTable() == null) {
                result.setSuccess(false);
                result.setError("无法识别查询的表名: " + description);
                return result;
            }

            // 4. 生成 SQL
            String sql = intent.toSql();
            result.setSql(sql);
            result.setTableName(intent.getTable());

            log.info("生成的 SQL: {}", sql);

            // 5. 执行查询（传入 db_id 路由到对应数据库）
            McpDatabaseClient.McpResponse response = client.query(sql,
                    intent.getLimit() != null ? intent.getLimit() : 1000, databaseId);

            if (!response.isSuccess()) {
                result.setSuccess(false);
                result.setError(response.getError());
                return result;
            }

            // 6. 解析查询结果
            List<Map<String, Object>> data = parseQueryResponse(response);
            result.setData(data);
            result.setRowCount(data.size());

            // 7. 获取表结构
            result.setSchema(getTableSchema(client, intent.getTable()));

            long duration = System.currentTimeMillis() - startTime;
            result.setDuration(duration);
            result.setSuccess(true);

            log.info("查询完成: rows={}, duration={} ms", data.size(), duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            result.setDuration(duration);
            result.setSuccess(false);
            result.setError(e.getMessage());
            log.error("查询失败: database={}, description={}", databaseId, description, e);
        }

        return result;
    }

    /**
     * 使用默认数据库查询
     *
     * @param description 自然语言描述
     * @return 查询结果
     */
    public QueryResult query(String description) {
        String defaultDatabase = multiDatabaseConfig.getDefaultDatabase();
        return query(defaultDatabase, description);
    }

    /**
     * 智能查询 - 根据自然语言描述自动选择数据库查询
     *
     * @param description 自然语言描述
     * @return 查询结果
     */
    public QueryResult smartQuery(String description) {
        String defaultDatabase = multiDatabaseConfig.getDefaultDatabase();
        return query(defaultDatabase, description);
    }

    /**
     * 智能查询 - 指定数据库根据自然语言描述查询
     *
     * @param databaseId  数据库标识符
     * @param description 自然语言描述
     * @return 查询结果
     */
    public QueryResult smartQuery(String databaseId, String description) {
        return query(databaseId, description);
    }

    /**
     * 执行原始 SQL 查询
     *
     * @param databaseId 数据库标识符
     * @param sql        SQL 语句
     * @return 查询结果
     */
    public QueryResult executeSql(String databaseId, String sql) {
        QueryResult result = new QueryResult();
        long startTime = System.currentTimeMillis();

        try {
            McpDatabaseClient client = getMcpClient();
            McpDatabaseClient.McpResponse response = client.query(sql, 1000, databaseId);

            if (!response.isSuccess()) {
                result.setSuccess(false);
                result.setError(response.getError());
                return result;
            }

            List<Map<String, Object>> data = parseQueryResponse(response);
            result.setData(data);
            result.setRowCount(data.size());
            result.setSql(sql);
            result.setSuccess(true);

            long duration = System.currentTimeMillis() - startTime;
            result.setDuration(duration);

            log.info("SQL 查询完成: rows={}, duration={} ms", data.size(), duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            result.setDuration(duration);
            result.setSuccess(false);
            result.setError(e.getMessage());
            log.error("SQL 查询失败: database={}, sql={}", databaseId, sql, e);
        }

        return result;
    }

    /**
     * 获取所有数据库列表
     *
     * @return 数据库信息列表
     */
    public Map<String, Object> listDatabases() {
        return multiDatabaseConfig.getSummary();
    }

    /**
     * 获取数据库的表列表
     *
     * @param databaseId 数据库标识符
     * @return 表列表
     */
    public List<String> listTables(String databaseId) {
        try {
            McpDatabaseClient client = getMcpClient();
            McpDatabaseClient.McpResponse response = client.listTables();

            if (response.isSuccess() && response.getContent() != null) {
                // 解析表列表
                List<String> tables = new ArrayList<>();
                String[] lines = response.getContent().split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("+") && !line.startsWith("| Tables_in")) {
                        tables.add(line.replace("|", "").trim());
                    }
                }
                return tables;
            }
        } catch (Exception e) {
            log.error("获取表列表失败: database={}", databaseId, e);
        }

        return Collections.emptyList();
    }

    /**
     * 获取表结构
     *
     * @param databaseId 数据库标识符
     * @param tableName  表名
     * @return 表结构信息
     */
    public TableSchemaInfo getTableStructure(String databaseId, String tableName) {
        try {
            McpDatabaseClient client = getMcpClient();
            return getTableSchema(client, tableName);
        } catch (Exception e) {
            log.error("获取表结构失败: database={}, table={}", databaseId, tableName, e);
            return null;
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取查询解析器（带缓存）
     */
    private NaturalLanguageQueryParser getParser(String databaseId) {
        return parserCache.computeIfAbsent(databaseId,
                id -> new NaturalLanguageQueryParser(multiDatabaseConfig, id));
    }

    /**
     * 解析查询响应
     */
    private List<Map<String, Object>> parseQueryResponse(McpDatabaseClient.McpResponse response) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (response.getContent() == null) {
            return result;
        }

        try {
            // 解析 Row 格式的数据
            String[] lines = response.getContent().split("\n");
            for (String line : lines) {
                if (line.contains("Row ") && line.contains(": {")) {
                    int braceStart = line.indexOf("{");
                    if (braceStart >= 0) {
                        String rowContent = line.substring(braceStart);
                        Map<String, Object> rowData = TextParserUtil.parseRow(rowContent);
                        if (!rowData.isEmpty()) {
                            result.add(rowData);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析查询响应失败", e);
        }

        return result;
    }

    /**
     * 获取表结构
     */
    private TableSchemaInfo getTableSchema(McpDatabaseClient client, String tableName) {
        TableSchemaInfo schema = new TableSchemaInfo();

        try {
            McpDatabaseClient.McpResponse response = client.describeTable(tableName);

            if (response.isSuccess() && response.getContent() != null) {
                String[] lines = response.getContent().split("\n");
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
            log.error("获取表结构失败: table={}", tableName, e);
        }

        return schema;
    }

    // ==================== 数据模型 ====================

    /**
     * 查询结果
     */
    @Data
    public static class QueryResult {
        /**
         * 是否成功
         */
        private boolean success = true;

        /**
         * 数据库标识符
         */
        private String databaseId;

        /**
         * 查询的表名
         */
        private String tableName;

        /**
         * SQL 语句
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
        private TableSchemaInfo schema;

        /**
         * 查询引擎
         */
        private String engine;

        /**
         * 获取查询引擎
         * 
         * @return 查询引擎
         */
        public String getEngine() {
            return engine;
        }

        /**
         * 设置查询引擎
         * 
         * @param engine 查询引擎
         */
        public void setEngine(String engine) {
            this.engine = engine;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getDatabaseId() {
            return databaseId;
        }

        public String getTableName() {
            return tableName;
        }

        public String getSql() {
            return sql;
        }

        public int getRowCount() {
            return rowCount;
        }

        public long getDuration() {
            return duration;
        }

        public List<Map<String, Object>> getData() {
            return data;
        }

        public TableSchemaInfo getSchema() {
            return schema;
        }

        public String getError() {
            return error;
        }
    }

    /**
     * 表结构信息
     */
    @Data
    public static class TableSchemaInfo {
        /**
         * 字段列表
         */
        private List<String> columns = new ArrayList<>();

        /**
         * 字段类型映射
         */
        private Map<String, String> columnTypes = new HashMap<>();
    }
}
