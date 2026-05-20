package io.yunxi.platform.business.nutrition.controller;

import io.yunxi.platform.shared.service.MultiDatabaseQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多数据库查询控制器
 * <p>
 * 提供统一的 REST API 接口，支持查询多个数据库（营养、经费、食安等）
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/multi-database")
public class MultiDatabaseQueryController {

    /** 多数据库查询服务（可选依赖，未启用时为null） */
    @Autowired(required = false)
    private MultiDatabaseQueryService multiDatabaseQueryService;

    /**
     * 根据自然语言描述查询数据（使用默认数据库）
     *
     * @param request 请求体，包含 description 字段
     * @return 查询结果
     */
    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        if (multiDatabaseQueryService == null) {
            response.put("success", false);
            response.put("error", "MultiDatabaseQueryService 未启用");
            return ResponseEntity.status(503).body(response);
        }

        String description = request.get("description");
        if (description == null || description.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "description 参数不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        String databaseId = request.getOrDefault("database", "default");

        try {
            MultiDatabaseQueryService.QueryResult result;

            if ("default".equals(databaseId)) {
                result = multiDatabaseQueryService.query(description);
            } else {
                result = multiDatabaseQueryService.query(databaseId, description);
            }

            response.put("success", result.isSuccess());

            if (result.isSuccess()) {
                response.put("databaseId", result.getDatabaseId());
                response.put("tableName", result.getTableName());
                response.put("sql", result.getSql());
                response.put("rowCount", result.getRowCount());
                response.put("duration", result.getDuration());
                response.put("data", result.getData());
                response.put("schema", result.getSchema());
            } else {
                response.put("error", result.getError());
            }

        } catch (Exception e) {
            log.error("查询失败: description={}", description, e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET 快捷查询（使用默认数据库）
     *
     * @param description 自然语言描述
     * @return 查询结果
     */
    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> quickQuery(@RequestParam String description) {
        Map<String, String> request = new HashMap<>();
        request.put("description", description);
        return query(request);
    }

    /**
     * 指定数据库查询
     *
     * @param databaseId  数据库标识符
     * @param description 自然语言描述
     * @return 查询结果
     */
    @GetMapping("/{databaseId}/query")
    public ResponseEntity<Map<String, Object>> queryByDatabase(
            @PathVariable String databaseId,
            @RequestParam String description) {

        Map<String, String> request = new HashMap<>();
        request.put("database", databaseId);
        request.put("description", description);
        return query(request);
    }

    /**
     * 执行原始 SQL 查询
     *
     * @param request 请求体，包含 database 和 sql 字段
     * @return 查询结果
     */
    @PostMapping("/execute-sql")
    public ResponseEntity<Map<String, Object>> executeSql(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        if (multiDatabaseQueryService == null) {
            response.put("success", false);
            response.put("error", "MultiDatabaseQueryService 未启用");
            return ResponseEntity.status(503).body(response);
        }

        String databaseId = request.get("database");
        String sql = request.get("sql");

        if (databaseId == null || databaseId.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "database 参数不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        if (sql == null || sql.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "sql 参数不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            MultiDatabaseQueryService.QueryResult result =
                    multiDatabaseQueryService.executeSql(databaseId, sql);

            response.put("success", result.isSuccess());

            if (result.isSuccess()) {
                response.put("sql", result.getSql());
                response.put("rowCount", result.getRowCount());
                response.put("duration", result.getDuration());
                response.put("data", result.getData());
            } else {
                response.put("error", result.getError());
            }

        } catch (Exception e) {
            log.error("SQL 查询失败: database={}, sql={}", databaseId, sql, e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 列出所有数据库
     *
     * @return 数据库列表
     */
    @GetMapping("/databases")
    public ResponseEntity<Map<String, Object>> listDatabases() {
        Map<String, Object> response = new HashMap<>();

        if (multiDatabaseQueryService == null) {
            response.put("success", false);
            response.put("error", "MultiDatabaseQueryService 未启用");
            return ResponseEntity.status(503).body(response);
        }

        try {
            Map<String, Object> summary = multiDatabaseQueryService.listDatabases();
            response.put("success", true);
            response.putAll(summary);

        } catch (Exception e) {
            log.error("获取数据库列表失败", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 列出指定数据库的所有表
     *
     * @param databaseId 数据库标识符
     * @return 表列表
     */
    @GetMapping("/{databaseId}/tables")
    public ResponseEntity<Map<String, Object>> listTables(@PathVariable String databaseId) {
        Map<String, Object> response = new HashMap<>();

        if (multiDatabaseQueryService == null) {
            response.put("success", false);
            response.put("error", "MultiDatabaseQueryService 未启用");
            return ResponseEntity.status(503).body(response);
        }

        try {
            List<String> tables = multiDatabaseQueryService.listTables(databaseId);
            response.put("success", true);
            response.put("databaseId", databaseId);
            response.put("tables", tables);

        } catch (Exception e) {
            log.error("获取表列表失败: database={}", databaseId, e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 获取表结构
     *
     * @param databaseId 数据库标识符
     * @param tableName  表名
     * @return 表结构信息
     */
    @GetMapping("/{databaseId}/tables/{tableName}/schema")
    public ResponseEntity<Map<String, Object>> getTableSchema(
            @PathVariable String databaseId,
            @PathVariable String tableName) {

        Map<String, Object> response = new HashMap<>();

        if (multiDatabaseQueryService == null) {
            response.put("success", false);
            response.put("error", "MultiDatabaseQueryService 未启用");
            return ResponseEntity.status(503).body(response);
        }

        try {
            MultiDatabaseQueryService.TableSchemaInfo schema =
                    multiDatabaseQueryService.getTableStructure(databaseId, tableName);

            response.put("success", true);
            response.put("databaseId", databaseId);
            response.put("tableName", tableName);
            response.put("schema", schema);

        } catch (Exception e) {
            log.error("获取表结构失败: database={}, table={}", databaseId, tableName, e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 健康检查
     *
     * @return 服务状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("service", "MultiDatabaseQueryService");
        response.put("enabled", multiDatabaseQueryService != null);
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    /**
     * 智能查询 — 优先使用 Text-to-SQL，失败回退到规则引擎
     *
     * <p>
     * 当 text2sql.enabled=true 时，优先通过 LLM 生成 SQL；
     * 否则使用基于正则/关键词的规则引擎查询。
     * </p>
     *
     * @param request 请求体，包含 description 字段和可选的 database 字段
     * @return 查询结果（含 engine 字段标识使用的引擎）
     */
    @PostMapping("/smart-query")
    public ResponseEntity<Map<String, Object>> smartQuery(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        if (multiDatabaseQueryService == null) {
            response.put("success", false);
            response.put("error", "MultiDatabaseQueryService 未启用");
            return ResponseEntity.status(503).body(response);
        }

        String description = request.get("description");
        if (description == null || description.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "description 参数不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        String databaseId = request.getOrDefault("database", "default");

        try {
            MultiDatabaseQueryService.QueryResult result;

            if ("default".equals(databaseId)) {
                result = multiDatabaseQueryService.smartQuery(description);
            } else {
                result = multiDatabaseQueryService.smartQuery(databaseId, description);
            }

            response.put("success", result.isSuccess());

            if (result.isSuccess()) {
                response.put("databaseId", result.getDatabaseId());
                response.put("tableName", result.getTableName());
                response.put("sql", result.getSql());
                response.put("rowCount", result.getRowCount());
                response.put("duration", result.getDuration());
                response.put("data", result.getData());
                response.put("schema", result.getSchema());
                response.put("engine", result.getEngine());
            } else {
                response.put("error", result.getError());
                response.put("engine", result.getEngine());
            }

        } catch (Exception e) {
            log.error("智能查询失败: description={}", description, e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET 快捷智能查询（使用默认数据库）
     *
     * @param description 自然语言描述
     * @return 查询结果
     */
    @GetMapping("/smart-query")
    public ResponseEntity<Map<String, Object>> quickSmartQuery(@RequestParam String description) {
        Map<String, String> request = new HashMap<>();
        request.put("description", description);
        return smartQuery(request);
    }
}
