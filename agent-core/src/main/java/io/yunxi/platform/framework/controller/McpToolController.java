package io.yunxi.platform.framework.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.yunxi.platform.framework.mcp.McpToolRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 工具管理控制器
 * 提供 MCP 工具刷新和状态查询的 REST 端点
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpToolController {

    private final McpToolRegistry mcpToolRegistry;

    /**
     * 刷新指定 MCP 服务器的工具
     * @param serverName MCP 服务器名称
     * @return 刷新结果
     */
    @PostMapping("/refresh/{serverName}")
    public ResponseEntity<Map<String, Object>> refreshMcpServer(@PathVariable String serverName) {
        log.info("收到刷新 MCP 服务器 [{}] 的请求", serverName);

        Map<String, Object> result = new HashMap<>();
        try {
            boolean success = mcpToolRegistry.refreshMcpServer(serverName);
            result.put("serverName", serverName);
            result.put("success", success);
            result.put("message", success ? "刷新成功" : "刷新失败");

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("刷新 MCP 服务器 [{}] 失败", serverName, e);
            result.put("serverName", serverName);
            result.put("success", false);
            result.put("message", "刷新失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 刷新所有 MCP 服务器的工具
     * @return 刷新结果
     */
    @PostMapping("/refresh-all")
    public ResponseEntity<Map<String, Object>> refreshAllMcpServers() {
        log.info("收到刷新所有 MCP 服务器的请求");

        Map<String, Object> result = new HashMap<>();
        try {
            int successCount = mcpToolRegistry.refreshAllMcpServers();
            result.put("successCount", successCount);
            result.put("message", "刷新完成，成功: " + successCount + " 个服务器");

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("刷新所有 MCP 服务器失败", e);
            result.put("successCount", 0);
            result.put("message", "刷新失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 获取所有 MCP 服务器的状态
     * @return 服务器状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getMcpServerStatus() {
        Map<String, Object> status = mcpToolRegistry.getServerStatus();
        return ResponseEntity.ok(status);
    }

    /**
     * 获取指定 MCP 服务器的工具列表
     * @param serverName MCP 服务器名称
     * @return 工具列表
     */
    @GetMapping("/tools/{serverName}")
    public ResponseEntity<Map<String, Object>> getMcpTools(@PathVariable String serverName) {
        Map<String, Object> result = new HashMap<>();
        result.put("serverName", serverName);
        result.put("tools", mcpToolRegistry.getCachedTools(serverName));

        return ResponseEntity.ok(result);
    }

    /**
     * 手动触发检查未连接的 MCP 服务器
     * @return 检查结果
     */
    @PostMapping("/reconnect")
    public ResponseEntity<Map<String, Object>> reconnectDisconnectedServers() {
        log.info("收到重新连接未连接的 MCP 服务器的请求");

        Map<String, Object> result = new HashMap<>();
        result.put("message", "已触发重新连接检查，定时任务会自动处理");

        return ResponseEntity.accepted().body(result);
    }
}
