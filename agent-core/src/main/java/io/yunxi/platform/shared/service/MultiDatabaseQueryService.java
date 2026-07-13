package io.yunxi.platform.shared.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.shared.config.MultiDatabaseConfig;
import io.yunxi.platform.shared.util.TextParserUtil;
import io.yunxi.platform.shared.util.nlq.NaturalLanguageQueryParser;
import io.yunxi.platform.sync.ExternalDbQueryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 多数据库查询服务
 * <p>
 * 通过 MultiDatabaseConfig 配置的 JDBC URL，路由并连接目标数据库执行查询。
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
    private ExternalDbQueryService externalDbQueryService;

    private final Map<String, NaturalLanguageQueryParser> parserCache = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 根据自然语言描述查询数据
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

            // 2. 解析查询意图
            NaturalLanguageQueryParser parser = getParser(databaseId);
            NaturalLanguageQueryParser.QueryIntent intent = parser.parse(description);

            if (intent.getTable() == null) {
                result.setSuccess(false);
                result.setError("无法识别查询的表名: " + description);
                return result;
            }

            // 3. 生成 SQL
            String sql = intent.toSql();
            result.setSql(sql);
            result.setTableName(intent.getTable());

            log.info("生成的 SQL: {}", sql);

            // 4. 执行查询（直接 JDBC 连接目标数据库）
            String jsonResult = externalDbQueryService.query(
                    dbInfo.getJdbcUrl(), dbInfo.getUsername(), dbInfo.getPassword(),
                    sql, intent.getLimit() != null ? intent.getLimit() : 1000);

            // 5. 解析查询结果
            List<Map<String, Object>> data = parseJsonResult(jsonResult);
            result.setData(data);
            result.setRowCount(data.size());

            // 6. 获取表结构
            result.setSchema(getTableSchema(databaseId, intent.getTable()));

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
     * @param description 自然语言查询描述
     * @return 查询结果
     */
    public QueryResult query(String description) {
        String defaultDatabase = multiDatabaseConfig.getDefaultDatabase();
        return query(defaultDatabase, description);
    }

    /**
     * 使用默认数据库进行智能查询
     *
     * @param description 自然语言查询描述
     * @return 查询结果
     */
    public QueryResult smartQuery(String description) {
        String defaultDatabase = multiDatabaseConfig.getDefaultDatabase();
        return query(defaultDatabase, description);
    }

    /**
     * 指定数据库进行智能查询
     *
     * @param databaseId  数据库标识
     * @param description 自然语言查询描述
     * @return 查询结果
     */
    public QueryResult smartQuery(String databaseId, String description) {
        return query(databaseId, description);
    }

    /**
     * 执行原始 SQL 查询
     *
     * @param databaseId 数据库标识
     * @param sql        待执行的 SQL 语句
     * @return 查询结果
     */
    public QueryResult executeSql(String databaseId, String sql) {
        QueryResult result = new QueryResult();
        long startTime = System.currentTimeMillis();

        try {
            MultiDatabaseConfig.DatabaseInfo dbInfo = multiDatabaseConfig.getDatabaseInfo(databaseId);
            if (dbInfo == null) {
                result.setSuccess(false);
                result.setError("数据库配置不存在: " + databaseId);
                return result;
            }

            String jsonResult = externalDbQueryService.query(
                    dbInfo.getJdbcUrl(), dbInfo.getUsername(), dbInfo.getPassword(), sql, 1000);

            List<Map<String, Object>> data = parseJsonResult(jsonResult);
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
     * 获取所有已配置数据库的摘要列表
     *
     * @return 数据库摘要映射（数据库标识 → 概要信息）
     */
    public Map<String, Object> listDatabases() {
        return multiDatabaseConfig.getSummary();
    }

    /**
     * 获取指定数据库的表列表
     *
     * @param databaseId 数据库标识
     * @return 表名列表，获取失败时返回空列表
     */
    public List<String> listTables(String databaseId) {
        try {
            MultiDatabaseConfig.DatabaseInfo dbInfo = multiDatabaseConfig.getDatabaseInfo(databaseId);
            if (dbInfo == null) {
                return Collections.emptyList();
            }

            String jsonResult = externalDbQueryService.listTables(
                    dbInfo.getJdbcUrl(), dbInfo.getUsername(), dbInfo.getPassword());
            List<Map<String, Object>> tables = parseJsonResult(jsonResult);

            List<String> result = new ArrayList<>();
            for (Map<String, Object> row : tables) {
                row.values().stream().findFirst()
                        .ifPresent(v -> result.add(String.valueOf(v)));
            }
            return result;
        } catch (Exception e) {
            log.error("获取表列表失败: database={}", databaseId, e);
        }
        return Collections.emptyList();
    }

    /**
     * 获取指定表的结构信息
     *
     * @param databaseId 数据库标识
     * @param tableName  表名
     * @return 表结构信息（列名与列类型映射）
     */
    public TableSchemaInfo getTableStructure(String databaseId, String tableName) {
        return getTableSchema(databaseId, tableName);
    }

    // ==================== 私有方法 ====================

    /**
     * 获取指定数据库的 NLQ 解析器（带缓存）
     *
     * @param databaseId 数据库 ID
     * @return 对应数据库的 {@link NaturalLanguageQueryParser} 实例
     */
    private NaturalLanguageQueryParser getParser(String databaseId) {
        return parserCache.computeIfAbsent(databaseId,
                id -> new NaturalLanguageQueryParser(multiDatabaseConfig, id));
    }

    /**
     * 解析 JSON 查询结果
     */
    private List<Map<String, Object>> parseJsonResult(String jsonResult) {
        try {
            if (jsonResult == null || jsonResult.isEmpty() || "[]".equals(jsonResult)) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(jsonResult,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("解析查询结果失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取表结构
     */
    private TableSchemaInfo getTableSchema(String databaseId, String tableName) {
        TableSchemaInfo schema = new TableSchemaInfo();
        try {
            MultiDatabaseConfig.DatabaseInfo dbInfo = multiDatabaseConfig.getDatabaseInfo(databaseId);
            if (dbInfo == null) {
                return schema;
            }

            String jsonResult = externalDbQueryService.describeTable(
                    dbInfo.getJdbcUrl(), dbInfo.getUsername(), dbInfo.getPassword(), tableName);

            List<Map<String, Object>> columns = parseJsonResult(jsonResult);
            for (Map<String, Object> col : columns) {
                String columnName = String.valueOf(col.getOrDefault("COLUMN_NAME", ""));
                String columnType = String.valueOf(col.getOrDefault("DATA_TYPE", ""));
                if (!columnName.isEmpty()) {
                    schema.getColumns().add(columnName);
                    schema.getColumnTypes().put(columnName, columnType);
                }
            }
        } catch (Exception e) {
            log.error("获取表结构失败: database={}, table={}", databaseId, tableName, e);
        }
        return schema;
    }

    // ==================== 数据模型 ====================

    /** 单次查询的结果封装，包含 SQL、数据行、表结构及耗时等信息 */
    @Data
    public static class QueryResult {
        private boolean success = true;
        private String databaseId;
        private String tableName;
        private String sql;
        private int rowCount;
        private List<Map<String, Object>> data = new ArrayList<>();
        private long duration;
        private String error;
        private TableSchemaInfo schema;
        private String engine;
    }

    /** 表结构信息，包含列名列表与列名到列类型的映射 */
    @Data
    public static class TableSchemaInfo {
        private List<String> columns = new ArrayList<>();
        private Map<String, String> columnTypes = new HashMap<>();
    }
}
