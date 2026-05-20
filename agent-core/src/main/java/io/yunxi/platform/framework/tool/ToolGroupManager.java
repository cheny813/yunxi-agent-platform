package io.yunxi.platform.framework.tool;

import io.agentscope.core.tool.Toolkit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具分组管理器
 * <p>
 * 实现 AgentScope ToolGroup 功能：
 * - 动态创建工具组（基于 MCP 服务器名称和本地工具类型）
 * - 工具自动归组
 * - 动态切换工具组激活状态
 * </p>
 * <p>
 * 工具组命名规则：
 * - MCP 工具组：组名 = MCP 服务器名称（如 database、milvus、formfill、pagegen）
 * - 本地工具组：agent（子Agent调用）、page（页面生成）、general（通用）
 * - SDK 内部工具（PlanNotebook、SkillBox）为未分组工具，始终可见
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class ToolGroupManager {

    /**
     * 运行时工具组注册表
     * key: 组名, value: 组配置
     */
    private final Map<String, RuntimeToolGroup> runtimeGroups = new ConcurrentHashMap<>();

    /**
     * 本地工具组定义（非 MCP 工具组）
     */
    private static final Map<String, String> LOCAL_TOOL_GROUPS = new LinkedHashMap<>();

    static {
        LOCAL_TOOL_GROUPS.put("agent", "子Agent调用工具");
        LOCAL_TOOL_GROUPS.put("page", "页面生成工具");
        LOCAL_TOOL_GROUPS.put("general", "通用本地工具");
    }

    /**
     * 运行时工具组配置
     */
    @Data
    public static class RuntimeToolGroup {
        /** 组ID */
        private String groupId;
        /** 组名称 */
        private String groupName;
        /** 是否激活 */
        private boolean active;
        /** 工具名称集合 */
        private Set<String> toolNames = new LinkedHashSet<>();

        public RuntimeToolGroup() {
        }

        public RuntimeToolGroup(String groupId, String groupName, boolean active) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.active = active;
        }
    }

    /**
     * 创建本地工具组
     * <p>
     * 在注册本地工具之前调用，创建 agent、page、general 三个标准工具组
     * </p>
     *
     * @param toolkit 工具包
     */
    public void createLocalToolGroups(Toolkit toolkit) {
        for (Map.Entry<String, String> entry : LOCAL_TOOL_GROUPS.entrySet()) {
            createGroup(toolkit, entry.getKey(), entry.getValue(), true);
        }
    }

    /**
     * 为指定的 MCP 服务器创建工具组
     * <p>
     * 在注册 MCP 工具之前调用，组名 = 服务器名称
     * </p>
     *
     * @param toolkit     工具包
     * @param serverNames MCP 服务器名称列表
     */
    public void createMcpToolGroups(Toolkit toolkit, List<String> serverNames) {
        if (serverNames == null || serverNames.isEmpty()) {
            return;
        }
        for (String serverName : serverNames) {
            createGroup(toolkit, serverName, serverName + " MCP 服务工具", true);
        }
    }

    /**
     * 创建单个工具组（幂等）
     *
     * @param toolkit         工具包
     * @param groupId         组ID
     * @param groupName       组名称
     * @param activeByDefault 是否默认激活
     */
    public void createGroup(Toolkit toolkit, String groupId, String groupName, boolean activeByDefault) {
        try {
            toolkit.createToolGroup(groupId, groupName, activeByDefault);
            runtimeGroups.computeIfAbsent(groupId, k -> new RuntimeToolGroup(groupId, groupName, activeByDefault));
            log.debug("创建工具组: {} - {} (默认激活: {})", groupId, groupName, activeByDefault);
        } catch (Exception e) {
            // 组已存在时忽略（重连场景）
            log.debug("工具组已存在或创建失败: {} - {}", groupId, e.getMessage());
        }
    }

    /**
     * 应用工具组激活策略
     * <p>
     * 如果指定了激活组列表，则停用所有组后仅激活指定组。
     * 未指定时，所有组保持默认激活状态（向后兼容）。
     * </p>
     *
     * @param toolkit      工具包
     * @param activeGroups 要激活的组列表，null 或空表示全部激活
     */
    public void applyGroupActivation(Toolkit toolkit, List<String> activeGroups) {
        if (activeGroups == null || activeGroups.isEmpty()) {
            log.debug("未指定激活组列表，所有工具组保持默认激活");
            return;
        }

        // 先停用所有已知组
        for (String groupId : runtimeGroups.keySet()) {
            try {
                toolkit.updateToolGroups(List.of(groupId), false);
            } catch (Exception e) {
                log.debug("停用工具组失败（可能不存在）: {} - {}", groupId, e.getMessage());
            }
        }

        // 激活指定组
        for (String groupId : activeGroups) {
            try {
                toolkit.updateToolGroups(List.of(groupId), true);
                log.info("激活工具组: {}", groupId);
            } catch (Exception e) {
                log.warn("激活工具组失败: {} - {}", groupId, e.getMessage());
            }
        }

        log.info("工具组激活完成，激活: {}", activeGroups);
    }

    /**
     * 动态切换工具组（运行时调用）
     *
     * @param toolkit     工具包
     * @param activateIds 要激活的工具组 ID 列表
     */
    public void activateGroups(Toolkit toolkit, List<String> activateIds) {
        if (toolkit == null || activateIds == null || activateIds.isEmpty()) {
            return;
        }

        // 激活指定组
        for (String groupId : activateIds) {
            try {
                toolkit.updateToolGroups(List.of(groupId), true);
                RuntimeToolGroup group = runtimeGroups.get(groupId);
                if (group != null) {
                    group.setActive(true);
                }
                log.info("激活工具组: {}", groupId);
            } catch (Exception e) {
                log.warn("激活工具组失败: {} - {}", groupId, e.getMessage());
            }
        }
    }

    /**
     * 停用指定工具组
     *
     * @param toolkit  工具包
     * @param groupIds 要停用的工具组 ID 列表
     */
    public void deactivateGroups(Toolkit toolkit, List<String> groupIds) {
        if (toolkit == null || groupIds == null || groupIds.isEmpty()) {
            return;
        }

        for (String groupId : groupIds) {
            try {
                toolkit.updateToolGroups(List.of(groupId), false);
                RuntimeToolGroup group = runtimeGroups.get(groupId);
                if (group != null) {
                    group.setActive(false);
                }
                log.info("停用工具组: {}", groupId);
            } catch (Exception e) {
                log.warn("停用工具组失败: {} - {}", groupId, e.getMessage());
            }
        }
    }

    /**
     * 获取当前已注册的工具组
     */
    public Map<String, RuntimeToolGroup> getRuntimeGroups() {
        return Collections.unmodifiableMap(runtimeGroups);
    }

    /**
     * 获取当前激活的工具组 ID 列表
     */
    public List<String> getActiveGroups() {
        return runtimeGroups.values().stream()
                .filter(RuntimeToolGroup::isActive)
                .map(RuntimeToolGroup::getGroupId)
                .toList();
    }

    /**
     * 根据工具名称推断所属本地工具组
     *
     * @param toolName 工具名称
     * @return 工具组名称
     */
    public static String resolveLocalToolGroup(String toolName) {
        if (toolName.startsWith("call_")) {
            return "agent";
        }
        if (toolName.startsWith("pagegen_")) {
            return "page";
        }
        return "general";
    }
}
