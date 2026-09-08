package io.yunxi.platform.controller;

import java.util.List;
import java.util.Map;

import io.yunxi.platform.agent.AgentConfigurer;
import io.yunxi.platform.agent.mcp.McpConfigStore;
import io.yunxi.platform.agent.mcp.McpServerEntry;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.McpNacosProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 服务器动态注册控制器。
 * <p>提供 MCP 服务器的注册 / 注销 / 列出接口：</p>
 * <ul>
 *   <li>POST /api/mcp/servers?name=xxx：注册（写入 Nacos 目录 + 本 JVM 注入工具组）</li>
 *   <li>DELETE /api/mcp/servers/{name}：注销</li>
 *   <li>GET /api/mcp/servers：列出当前目录</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp/servers")
public class McpController {

    private final AgentConfigurer agentConfigurer;
    private final McpConfigStore configStore;
    private final McpNacosProperties nacosProps;

    public McpController(AgentConfigurer agentConfigurer, McpConfigStore configStore,
            McpNacosProperties nacosProps) {
        this.agentConfigurer = agentConfigurer;
        this.configStore = configStore;
        this.nacosProps = nacosProps;
    }

    /**
     * 列出目录中所有 MCP 服务器（从 Nacos Naming 列出全部 mcp-server-* 实例，
     * 返回结构对齐 MCP 官方 server.json 规范，含 name/transport/url/status 等）。
     * Naming 未启用时降级到内存目录。
     */
    @GetMapping
    public List<McpServerEntry> list() {
        return configStore.listServerEntries();
    }

    /** 注册（或更新）一个 MCP 服务器。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> register(
            @RequestParam String name,
            @RequestBody AgentscopeCoreProperties.McpServerConfig config) {
        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name 不能为空"));
        }
        config.setEnabled(true);
        configStore.saveServer(name, config);
        configStore.registerServerInstance(name, config);  // 注册 Naming 实例（ephemeral 健康检查）
        agentConfigurer.registerDynamicMcpServer(name, config);
        log.info("MCP 服务器注册成功：{}", name);
        return ResponseEntity.ok(Map.of(
                "name", name,
                "status", "registered",
                "coordinator", nacosProps.getServerAddr()));
    }

    /** 注销一个 MCP 服务器。 */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> unregister(@PathVariable String name) {
        configStore.removeServer(name);
        configStore.deregisterServerInstance(name);  // 注销 Naming 实例
        agentConfigurer.unregisterDynamicMcpServer(name);
        log.info("MCP 服务器注销成功：{}", name);
        return ResponseEntity.ok(Map.of("name", name, "status", "unregistered"));
    }
}
