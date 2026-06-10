package io.yunxi.platform.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MCP 工具控制器（V2.0: MCP 支持已移除）
 * <p>所有端点均返回"不支持"，因为 V2.0 中已移除 MCP 工具注册表。</p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpToolController {

    private static final String NOT_SUPPORTED = "MCP工具管理已在V2.0中移除";

    @PostMapping("/refresh/{serverName}")
    public ResponseEntity<Map<String, Object>> refreshMcpServer(@PathVariable String serverName) {
        log.info("MCP刷新请求 [{}] - V2.0不支持", serverName);
        return ResponseEntity.ok(Map.of("serverName", serverName, "success", false, "message", NOT_SUPPORTED));
    }

    @PostMapping("/refresh-all")
    public ResponseEntity<Map<String, Object>> refreshAllMcpServers() {
        log.info("MCP全部刷新请求 - V2.0不支持");
        return ResponseEntity.ok(Map.of("successCount", 0, "message", NOT_SUPPORTED));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getMcpServerStatus() {
        return ResponseEntity.ok(Map.of("message", NOT_SUPPORTED));
    }

    @GetMapping("/tools/{serverName}")
    public ResponseEntity<Map<String, Object>> getMcpTools(@PathVariable String serverName) {
        return ResponseEntity.ok(Map.of("serverName", serverName, "tools", Map.of(), "message", NOT_SUPPORTED));
    }

    @PostMapping("/reconnect")
    public ResponseEntity<Map<String, Object>> reconnectDisconnectedServers() {
        log.info("MCP重连请求 - V2.0不支持");
        return ResponseEntity.ok(Map.of("message", NOT_SUPPORTED));
    }
}
