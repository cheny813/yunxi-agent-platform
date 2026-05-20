package io.yunxi.platform.framework.tool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心
 * <p>
 * 负责工具的注册、查询和管理
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class ToolRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * 工具存储
     * key: 工具名称
     * value: Tool 实例
     */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 工具类型索引
     * key: 工具类型标签
     * value: 工具名称列表
     */
    private final Map<String, List<String>> typeIndex = new ConcurrentHashMap<>();

    /**
     * 注册工具
     *
     * @param tool 工具实例
     */
    public void registerTool(Tool tool) {
        String toolName = tool.getName();

        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }

        tools.put(toolName, tool);
        log.info("工具注册成功: {} - {}", toolName, tool.getDescription());

        // 提取工具类型（从描述中）
        String toolType = extractToolType(tool);
        if (toolType != null) {
            typeIndex.computeIfAbsent(toolType, k -> new ArrayList<>()).add(toolName);
        }
    }

    /**
     * 获取工具
     *
     * @param name 工具名称
     * @return 工具实例，如果不存在返回 null
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册的工具
     *
     * @return 工具集合
     */
    public Collection<Tool> getAllTools() {
        return tools.values();
    }

    /**
     * 获取所有启用的工具
     *
     * @return 工具集合
     */
    public Collection<Tool> getEnabledTools() {
        return tools.values().stream()
                .filter(tool -> tool.isEnabled())
                .toList();
    }

    /**
     * 检查工具是否存在
     *
     * @param name 工具名称
     * @return true 如果存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取工具数量
     *
     * @return 工具总数
     */
    public int getToolCount() {
        return tools.size();
    }

    /**
     * 获取启用工具数量
     *
     * @return 启用工具数
     */
    public int getEnabledToolCount() {
        return (int) tools.values().stream().filter(tool -> tool.isEnabled()).count();
    }

    /**
     * 注销工具
     *
     * @param name 工具名称
     * @return 被注销的工具实例
     */
    public Tool unregisterTool(String name) {
        Tool tool = tools.remove(name);
        if (tool != null) {
            log.info("工具已注销: {}", name);

            // 从类型索引中移除
            typeIndex.values().forEach(list -> list.remove(name));
        }
        return tool;
    }

    /**
     * 获取工具 Schema 列表（用于 Agent）
     *
     * @return 工具 Schema 列表
     */
    public List<Map<String, Object>> getToolSchemas() {
        return tools.values().stream()
                .filter(tool -> tool.isEnabled())
                .map(tool -> {
                    Map<String, Object> schema = new HashMap<>();
                    schema.put("name", tool.getName());
                    schema.put("description", tool.getDescription());
                    schema.put("parameters", tool.getParameterSchema());
                    schema.put("enabled", tool.isEnabled());
                    return schema;
                })
                .toList();
    }

    /**
     * 按类型获取工具
     *
     * @param type 工具类型
     * @return 工具列表
     */
    public List<Tool> getToolsByType(String type) {
        List<String> toolNames = typeIndex.get(type);
        if (toolNames == null) {
            return List.of();
        }

        return toolNames.stream()
                .map(name -> this.getTool(name))
                .filter(tool -> Objects.nonNull(tool))
                .toList();
    }

    /**
     * 获取工具统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
                "totalTools", tools.size(),
                "enabledTools", getEnabledToolCount(),
                "toolTypes", typeIndex.size(),
                "toolTypeDetails", new HashMap<>(typeIndex));
    }

    /**
     * 提取工具类型（启发式方法）
     *
     * @param tool 工具实例
     * @return 工具类型
     */
    private String extractToolType(Tool tool) {
        String description = tool.getDescription().toLowerCase();

        if (description.contains("http") || description.contains("api") || description.contains("网络")) {
            return "network";
        }
        if (description.contains("数据") || description.contains("sql") || description.contains("查询")) {
            return "database";
        }
        if (description.contains("文件") || description.contains("file")) {
            return "file";
        }
        if (description.contains("天气") || description.contains("weather")) {
            return "weather";
        }
        if (description.contains("计算") || description.contains("数学")) {
            return "calculator";
        }

        return "general";
    }

    /**
     * 初始化（打印已注册的工具）
     */
    @PostConstruct
    public void init() {
        log.info("工具注册中心初始化完成，共 {} 个工具", tools.size());
        tools.forEach((name, tool) -> log.info("  - {}: {}", name, tool.getDescription()));
    }

}
