package io.yunxi.platform.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端服务。
 *
 * <p>
 * 负责与 MCP 服务器通信，调用工具、获取工具列表。
 * 使用 JSON-RPC 2.0 协议与 MCP 服务器交互，支持 HTTP 和 SSE 两种传输模式。
 * </p>
 *
 * <p>
 * 保留自 V2.0 升级：底层 MCP 协议实现，不属于框架封装层。
 * 管理端的工具注册和缓存由 V2.0 McpServerRegistrar 处理。
 * </p>
 *
 * <p>
 * SSE 模式 URL 转换规则：
 * - 工具调用：/sse → /message
 * - 工具列表：/sse → /tools
 * </p>
 */
@Slf4j
@Service
public class McpClientService {

    /** HTTP 客户端，用于调用 MCP 服务器 API */
    private final RestTemplate restTemplate;

    /** MCP 客户端配置 */
    private final McpClientConfig config;

    /** 核心配置属性，包含 MCP 服务器连接详情 */
    private final AgentscopeCoreProperties properties;

    /**
     * 构造 MCP 客户端服务。
     *
     * @param config     MCP 客户端配置
     * @param properties 核心配置属性
     */
    public McpClientService(McpClientConfig config, AgentscopeCoreProperties properties) {
        this.config = config;
        this.properties = properties;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 调用 MCP 工具。
     *
     * <p>
     * 调用流程：
     * 1. 检查 MCP 功能是否启用
     * 2. 查找 MCP 服务器配置
     * 3. 处理 SSE 模式的 URL 转换（/sse → /message）
     * 4. 构建JSON-RPC 2.0 请求体
     * 5. 注入 OpenTelemetry Trace ID 到请求头
     * 6. 发送 HTTP POST 请求并返回结果
     * </p>
     *
     * @param serverName MCP 服务器名称
     * @param toolName   工具名称
     * @param arguments  工具参数
     * @return 工具调用结果，MCP 未启用或服务器未找到时返回 null
     * @throws RuntimeException 调用失败时抛出
     */
    @SuppressWarnings("unchecked")
    public Object callTool(String serverName, String toolName, Map<String, Object> arguments) {
        // 检查 MCP 功能是否启用
        if (!config.isEnabled()) {
            log.warn("MCP 功能未启用");
            return null;
        }

        // 查找 MCP 服务器配置
        Map<String, AgentscopeCoreProperties.McpServerConfig> servers = properties.getMcpServers();
        if (servers == null) {
            log.warn("MCP 服务器配置为空");
            return null;
        }
        AgentscopeCoreProperties.McpServerConfig serverConfig = servers.get(serverName);
        if (serverConfig == null || !serverConfig.isEnabled()) {
            log.warn("MCP 服务器未找到或未启用: {}", serverName);
            return null;
        }

        // 解析服务器 URL 和类型
        String url = serverConfig.getUrl();
        String serverType = serverConfig.getType() != null ? serverConfig.getType() : "http";

        // SSE 模式：将 /sse 替换为 /message
        if ("sse".equalsIgnoreCase(serverType) && url.endsWith("/sse")) {
            url = url.replace("/sse", "/message");
        }

        // 获取当前链路追踪 Trace ID，注入到 MCP 请求头中
        SpanContext spanContext = Span.current().getSpanContext();
        String traceId = spanContext.isValid() ? spanContext.getTraceId() : null;

        try {
            // 构建 JSON-RPC 2.0 请求体
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

            // 设置请求头，注入 Trace ID
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (traceId != null) {
                headers.set("X-Trace-Id", traceId);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            log.info("[traceId={}] 调用 MCP 工具: {}.{}, URL: {}", traceId, serverName, toolName, url);
            log.debug("请求体: {}", new ObjectMapper().writeValueAsString(request));

            // 发送 HTTP POST 请求
            Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);

            log.info("[traceId={}] MCP 工具调用完成: {}.{}", traceId, serverName, toolName);

            return response;

        } catch (Exception e) {
            log.error("[traceId={}] 调用 MCP 工具失败: {}.{}, URL: {}", traceId, serverName, toolName, url, e);
            throw new RuntimeException("调用 MCP 工具失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取指定 MCP 服务器的工具列表。
     *
     * <p>
     * 查询流程：
     * 1. 查找 MCP 服务器配置
     * 2. 处理 SSE 模式的 URL 转换（/sse → /tools）
     * 3. 发送 HTTP GET 请求获取工具列表
     * </p>
     *
     * @param serverName MCP 服务器名称
     * @return 工具列表，服务器未找到时返回 null
     * @throws RuntimeException 获取失败时抛出
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTools(String serverName) {
        // 查找 MCP 服务器配置
        Map<String, AgentscopeCoreProperties.McpServerConfig> servers = properties.getMcpServers();
        if (servers == null) {
            log.warn("MCP 服务器配置为空");
            return null;
        }
        AgentscopeCoreProperties.McpServerConfig serverConfig = servers.get(serverName);
        if (serverConfig == null || !serverConfig.isEnabled()) {
            log.warn("MCP 服务器未找到或未启用: {}", serverName);
            return null;
        }

        // 解析工具列表 URL
        String url = serverConfig.getUrl();
        if (url.endsWith("/sse")) {
            // SSE 模式：/sse → /tools
            url = url.replace("/sse", "/tools");
        } else if (!url.endsWith("/tools")) {
            // HTTP 模式：追加 /tools 路径
            url = url + "/tools";
        }

        try {
            log.info("获取 MCP 工具列表: {}, URL: {}", serverName, url);
            List<Map<String, Object>> response = restTemplate.getForObject(url, List.class);
            log.info("工具列表获取成功: {}", serverName);
            return response;
        } catch (Exception e) {
            log.error("获取工具列表失败: {}, URL: {}", serverName, url, e);
            throw new RuntimeException("获取工具列表失败: " + e.getMessage(), e);
        }
    }
}
