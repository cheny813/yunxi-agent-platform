package io.yunxi.platform.framework.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 查询服务
 *
 * <p>
 * 封装 MCP 数据库工具的 HTTP JSON-RPC 调用逻辑，
 * 从 BaseSyncService 中提取，作为独立服务供同步类使用。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class McpQueryService {

    /** HTTP 请求模板 */
    private final RestTemplate restTemplate;
    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数
     */
    public McpQueryService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 通过 MCP 调用通用工具
     *
     * @param host      MCP 服务地址
     * @param port      MCP 服务端口
     * @param toolName  工具名称（如: query_db, describe_table 等）
     * @param arguments 工具参数
     * @return 工具执行结果
     */
    public String callMcpTool(String host, int port, String toolName, Map<String, Object> arguments) {
        String url = String.format("http://%s:%d/mcp", host, port);

        log.info("MCP 工具调用: url={}, tool={}", url, toolName);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("id", 1);
        requestBody.put("method", "tools/call");

        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        requestBody.put("params", params);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            String responseBody = response.getBody();
            if (responseBody != null && responseBody.length() < 500) {
                log.info("MCP 响应: {}", responseBody);
            } else if (responseBody != null) {
                log.info("MCP 响应长度: {}", responseBody.length());
            }

            return responseBody;
        } catch (Exception e) {
            log.error("MCP 工具调用失败: url={}, tool={}", url, toolName, e);
            throw new RuntimeException("MCP 工具调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过 MCP 调用外部数据库执行SQL查询
     *
     * @param host MCP 数据库服务地址
     * @param port MCP 数据库服务端口
     * @param sql  SQL 查询语句
     * @return 查询结果
     */
    public String callMcpDatabase(String host, int port, String sql) {
        return callMcpDatabase(host, port, sql, 10000);
    }

    /**
     * 通过 MCP 调用外部数据库执行SQL查询
     *
     * @param host  MCP 数据库服务地址
     * @param port  MCP 数据库服务端口
     * @param sql   SQL 查询语句
     * @param limit 返回结果数量限制
     * @return 查询结果
     */
    public String callMcpDatabase(String host, int port, String sql, int limit) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("sql", sql);
        arguments.put("limit", limit);
        return callMcpTool(host, port, "query_db", arguments);
    }

    /**
     * 通过 MCP 获取表结构（describe_table）
     *
     * @param host      MCP 数据库服务地址
     * @param port      MCP 数据库服务端口
     * @param tableName 表名
     * @return 表结构响应
     */
    public String getTableStructure(String host, int port, String tableName) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("table_name", tableName);
        return callMcpTool(host, port, "describe_table", arguments);
    }
}
