package io.yunxi.app.mcp;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用平面。MCP 动态注册运行时 REST API。
 *
 * <p>所有接口位于 {@code /api/mcp/servers}，受平台统一安全模型保护。列出/注册/注销服务器，
 * 注册与注销会经 {@link NacosMcpRegistrationSource} 同步内存权威目录并回写 Nacos 全量快照。
 */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerController {

    private final NacosMcpRegistrationSource source;

    public McpServerController(NacosMcpRegistrationSource source) {
        this.source = source;
    }

    /** 列出当前内存权威目录中的全部 MCP 服务器。 */
    @GetMapping
    public List<McpServerConfig> list() {
        return source.currentEntries();
    }

    /** 注册一个 MCP 服务器，并经协调底座同步目录与回写全量快照。 */
    @PostMapping
    public ResponseEntity<Void> register(
            @RequestParam("name") String name, @RequestBody McpServerConfig cfg) {
        if (cfg.getName() == null) {
            cfg.setName(name);
        }
        source.register(cfg);
        return ResponseEntity.ok().build();
    }

    /** 注销指定名称的 MCP 服务器，并同步目录与快照。 */
    @DeleteMapping
    public ResponseEntity<Void> unregister(@RequestParam("name") String name) {
        source.unregister(name);
        return ResponseEntity.ok().build();
    }
}
