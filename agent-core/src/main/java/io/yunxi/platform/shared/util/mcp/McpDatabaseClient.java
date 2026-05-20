package io.yunxi.platform.shared.util.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 数据库客户端
 * <p>
 * 提供统一的 MCP 数据库调用接口，支持多数据库连接配置
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
public class McpDatabaseClient {

    private final String host;
    private final int port;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建 MCP 数据库客户端
     *
     * @param host MCP 服务地址
     * @param port MCP 服务端口
     */
    public McpDatabaseClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用 MCP 工具
     *
     * @param toolName  工具名称（如: query_db, describe_table, list_tables 等）
     * @param arguments 工具参数
     * @return 工具执行结果（JSON 字符串）
     */
    public McpResponse callTool(String toolName, Map<String, Object> arguments) {
        String url = String.format("http://%s:%d/mcp", host, port);

        log.debug("调用 MCP 工具: {} @ {}", toolName, url);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("id", System.currentTimeMillis());
        requestBody.put("method", "tools/call");

        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        requestBody.put("params", params);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                McpResponse mcpResponse = new McpResponse();
                mcpResponse.setSuccess(true);
                mcpResponse.setResponseBody(response.getBody());
                mcpResponse.setStatusCode(response.getStatusCodeValue());

                // 尝试解析 JSON 响应
                if (response.getBody() != null) {
                    try {
                        JsonNode root = objectMapper.readTree(response.getBody());
                        JsonNode result = root.path("result");
                        if (result.isObject()) {
                            JsonNode content = result.path("content");
                            if (content.isArray() && content.size() > 0) {
                                JsonNode firstContent = content.get(0);
                                if (firstContent.has("text")) {
                                    mcpResponse.setContent(firstContent.path("text").asText());
                                } else if (firstContent.has("error")) {
                                    mcpResponse.setSuccess(false);
                                    mcpResponse.setError(firstContent.path("error").asText());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析 MCP 响应 JSON 失败: {}", e.getMessage());
                    }
                }

                return mcpResponse;
            } else {
                McpResponse mcpResponse = new McpResponse();
                mcpResponse.setSuccess(false);
                mcpResponse.setStatusCode(response.getStatusCodeValue());
                mcpResponse.setError("HTTP " + response.getStatusCode());
                return mcpResponse;
            }

        } catch (Exception e) {
            log.error("调用 MCP 工具失败: toolName={}", toolName, e);
            McpResponse mcpResponse = new McpResponse();
            mcpResponse.setSuccess(false);
            mcpResponse.setError(e.getMessage());
            return mcpResponse;
        }
    }

/**
     * 执行 SQL 查询
     *
     * @param sql   SQL 查询语句
     * @param limit 返回结果数量限制
     * @param dbId  数据库标识符（如 nutrition, finance），null 则使用默认库
     * @return MCP 响应
     */
    public McpResponse query(String sql, int limit, String dbId) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("sql", sql);
        arguments.put("limit", limit);
        if (dbId != null && !dbId.isBlank()) {
            arguments.put("db_id", dbId);
        }
        return callTool("query_db", arguments);
    }

    /**
     * 执行 SQL 查询（使用默认数据库）
     *
     * @param sql   SQL 查询语句
     * @param limit 返回结果数量限制
     * @return MCP 响应
     */
    public McpResponse query(String sql, int limit) {
        return query(sql, limit, null);
    }

    /**
     * 执行 SQL 查询（使用默认数据库，limit=1000）
     *
     * @param sql   SQL 查询语句
     * @return MCP 响应
     */
    public McpResponse query(String sql) {
        return query(sql, 1000, null);
    }

    /**
     * 获取表结构
     *
     * @param tableName 表名
     * @return MCP 响应
     */
    public McpResponse describeTable(String tableName) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("table_name", tableName);
        return callTool("describe_table", arguments);
    }

    /**
     * 列出所有表
     *
     * @return MCP 响应
     */
    public McpResponse listTables() {
        Map<String, Object> arguments = new HashMap<>();
        return callTool("list_tables", arguments);
    }

    /**
     * MCP 响应对象
     */
    @Data
    public static class McpResponse {
        /**
         * 是否成功
         */
        private boolean success = true;

        /**
         * HTTP 状态码
         */
        private Integer statusCode;

        /**
         * 原始响应体
         */
        private String responseBody;

        /**
         * 解析后的内容（text 字段）
         */
        private String content;

        /**
         * 错误信息
         */
        private String error;

        /**
         * 获取 JSON 根节点
         */
        public JsonNode getJsonRoot() {
            if (responseBody == null) {
                return null;
            }
            try {
                return new ObjectMapper().readTree(responseBody);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // Getter 方法
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
