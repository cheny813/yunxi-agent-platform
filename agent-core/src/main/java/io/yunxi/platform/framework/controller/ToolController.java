package io.yunxi.platform.framework.controller;

import org.springframework.web.bind.annotation.*;

import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolExecutionException;
import io.yunxi.platform.framework.tool.ToolInput;
import io.yunxi.platform.framework.tool.ToolRegistry;
import io.yunxi.platform.framework.tool.ToolResult;
import io.yunxi.platform.shared.exception.NotFoundException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具管理控制器
 * <p>
 * 提供工具的查询、管理和调用 API
 * </p>
 *
 * @author yunxi-agent-platform
 */
@RestController
@RequestMapping("/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 列出所有可用工具
     *
     * GET /tools
     *
     * Response:
     * [
     * {
     * "name": "http_request",
     * "description": "发送HTTP请求...",
     * "parameters": {...},
     * "enabled": true
     * }
     * ]
     */
    @GetMapping
    public Collection<Tool> listTools() {
        return toolRegistry.getEnabledTools();
    }

    /**
     * 获取工具 Schema 列表（用于Agent）
     *
     * GET /tools/schemas
     *
     * Response:
     * [
     * {
     * "name": "http_request",
     * "description": "发送HTTP请求...",
     * "parameters": "{...}"
     * }
     * ]
     */
    @GetMapping("/schemas")
    public List<Map<String, Object>> getToolSchemas() {
        return toolRegistry.getToolSchemas();
    }

    /**
     * 获取指定工具的信息
     *
     * GET /tools/{name}
     */
    @GetMapping("/{name}")
    public Map<String, Object> getTool(@PathVariable String name) {
        Tool tool = toolRegistry.getTool(name);
        if (tool == null) {
            throw new NotFoundException("工具不存在: " + name);
        }

        return Map.of(
                "name", tool.getName(),
                "description", tool.getDescription(),
                "parameters", tool.getParameterSchema(),
                "enabled", tool.isEnabled());
    }

    /**
     * 手动调用工具（测试用）
     *
     * POST /tools/{name}/execute
     *
     * Request Body:
     * {
     * "url": "https://api.example.com/data",
     * "method": "GET"
     * }
     *
     * Response:
     * {
     * "success": true,
     * "result": {...},
     * "resultType": "Map",
     * "durationMs": 123
     * }
     */
    @PostMapping("/{name}/execute")
    public ToolResult executeTool(
            @PathVariable String name,
            @RequestBody Map<String, Object> parameters) {
        Tool tool = toolRegistry.getTool(name);
        if (tool == null) {
            throw new NotFoundException("工具不存在: " + name);
        }

        if (!tool.isEnabled()) {
            throw new IllegalStateException("工具未启用: " + name);
        }

        ToolInput input = new ToolInput(parameters);

        try {
            return tool.execute(input);
        } catch (ToolExecutionException e) {
            throw new RuntimeException("工具执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取工具统计信息
     *
     * GET /tools/_stats
     */
    @GetMapping("/_stats")
    public Map<String, Object> getStatistics() {
        return toolRegistry.getStatistics();
    }

    /**
     * 按类型获取工具
     *
     * GET /tools/by-type?type=network
     */
    @GetMapping("/by-type")
    public List<Map<String, Object>> getToolsByType(@RequestParam String type) {
        List<Tool> tools = toolRegistry.getToolsByType(type);
        return tools.stream()
                .map(tool -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", tool.getName());
                    map.put("description", tool.getDescription());
                    map.put("enabled", tool.isEnabled());
                    return map;
                })
                .collect(Collectors.toList());
    }
}
