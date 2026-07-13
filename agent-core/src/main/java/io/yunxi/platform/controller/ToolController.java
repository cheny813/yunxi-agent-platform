package io.yunxi.platform.controller;

import java.util.Collection;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.yunxi.platform.shared.exception.NotFoundException;

/**
 * 工具管理控制器
 * <p>
 * 提供工具的查询和管理 API，底层使用AgentScope的Toolkit。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@RestController
@RequestMapping("/tools")
public class ToolController {

    private final Toolkit toolkit;

    /**
     * 构造工具管理控制器
     *
     * @param toolkit AgentScope 工具箱，承载所有已注册工具
     */
    public ToolController(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    /**
     * 列出所有已注册工具名称
     *
     * @return 工具名称集合
     */
    @GetMapping
    public Collection<String> listTools() {
        return toolkit.getToolNames();
    }

    /**
     * 获取指定工具的详情
     *
     * @param name 工具名称
     * @return 包含工具名称与描述的映射；工具不存在时抛出 NotFoundException
     */
    @GetMapping("/{name}")
    public Map<String, Object> getTool(@PathVariable String name) {
        AgentTool tool = toolkit.getTool(name);
        if (tool == null) {
            throw new NotFoundException("工具不存在: " + name);
        }
        return Map.of(
                "name", tool.getName(),
                "description", tool.getDescription());
    }
}
