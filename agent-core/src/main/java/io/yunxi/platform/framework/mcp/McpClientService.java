package io.yunxi.platform.framework.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.framework.tracing.TraceContext;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * MCP 客户端服务
 * <p>
 * 负责与MCP服务器通信，调用工具
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class McpClientService {

    /** HTTP 请求模板 */
    private final RestTemplate restTemplate;
    /** MCP 客户端配置 */
    private final McpClientConfig config;
    /** AgentScope 核心配置属性（提供 mcpServers） */
    private final AgentscopeCoreProperties properties;

    /**
     * 构造函数
     *
     * @param config     MCP 客户端配置
     * @param properties AgentScope 核心配置属性
     */
    @Autowired
    public McpClientService(McpClientConfig config, AgentscopeCoreProperties properties) {
        this.config = config;
        this.properties = properties;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 调用MCP工具
     *
     * @param serverName 服务器名称
     * @param toolName   工具名称
     * @param arguments  工具参数
     * @return 工具调用结果
     */
    public Object callTool(String serverName, String toolName, Map<String, Object> arguments) {
        if (!config.isEnabled()) {
            log.warn("MCP功能未启用");
            return null;
        }

        Map<String, AgentscopeCoreProperties.McpServerConfig> servers = properties.getMcpServers();
        if (servers == null) {
            log.warn("MCP服务器配置为空");
            return null;
        }
        AgentscopeCoreProperties.McpServerConfig serverConfig = servers.get(serverName);
        if (serverConfig == null || !serverConfig.isEnabled()) {
            log.warn("MCP服务器未找到或未启用: {}", serverName);
            return null;
        }

        String url = serverConfig.getUrl();
        String serverType = serverConfig.getType() != null ? serverConfig.getType() : "http";

        // SSE 模式：将 /sse 替换为 /message
        if ("sse".equalsIgnoreCase(serverType) && url.endsWith("/sse")) {
            url = url.replace("/sse", "/message");
        }

        // 获取当前 TraceId 并传递到请求头
        String traceId = TraceContext.getCurrentTraceId();

        try {
            // 构建JSON-RPC请求
            Map<String, Object> request = new HashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", System.currentTimeMillis());
            request.put("method", "tools/call");
            request.put("params", new HashMap<String, Object>() {
                {
                    put("name", toolName);
                    put("arguments", arguments);
                }
            });

            // 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (traceId != null) {
                headers.set(TraceContext.TRACE_ID_HEADER, traceId);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            log.info("[traceId={}] 调用MCP工具: {}.{}, URL: {}", traceId, serverName, toolName, url);
            log.debug("请求体: {}", new ObjectMapper().writeValueAsString(request));

            // 发送请求
            Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);

            log.info("[traceId={}] MCP工具调用完成: {}.{}, 响应: {}", traceId, serverName, toolName, response);

            return response;

        } catch (Exception e) {
            log.error("[traceId={}] 调用MCP工具失败: {}.{}, URL: {}", traceId, serverName, toolName, url, e);
            throw new RuntimeException("调用MCP工具失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取工具列表
     *
     * @param serverName 服务器名称
     * @return 工具列表
     */
    public List<Map<String, Object>> listTools(String serverName) {
        Map<String, AgentscopeCoreProperties.McpServerConfig> servers = properties.getMcpServers();
        if (servers == null) {
            log.warn("MCP服务器配置为空");
            return null;
        }
        AgentscopeCoreProperties.McpServerConfig serverConfig = servers.get(serverName);
        if (serverConfig == null || !serverConfig.isEnabled()) {
            log.warn("MCP服务器未找到或未启用: {}", serverName);
            return null;
        }

        String url = serverConfig.getUrl();

        // 构建工具列表URL：统一使用 /tools 端点
        // MCP服务器都支持 GET /mcp/tools 端点获取工具列表
        if (url.endsWith("/sse")) {
            // SSE类型URL：http://host:port/mcp/sse -> http://host:port/mcp/tools
            url = url.replace("/sse", "/tools");
        } else if (!url.endsWith("/tools")) {
            // 其他URL：添加 /tools 后缀
            url = url + "/tools";
        }

        try {
            log.info("获取MCP工具列表: {}, URL: {}", serverName, url);
            List<Map<String, Object>> response = restTemplate.getForObject(url, List.class);
            log.info("工具列表获取成功: {}", serverName);
            return response;
        } catch (Exception e) {
            log.error("获取工具列表失败: {}, URL: {}", serverName, url, e);
            throw new RuntimeException("获取工具列表失败: " + e.getMessage(), e);
        }
    }
}
