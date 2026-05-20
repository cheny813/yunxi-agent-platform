package io.yunxi.platform.shared.config;

import java.util.List;
import java.util.Map;

/**
 * 工具配置 - Agent 的工具和行为能力
 *
 * @author yunxi-agent-platform
 */
public class ToolConfig {

    /** 是否启用工具 */
    private boolean enabled = true;

    /** 激活的工具组列表（未列出的组对 LLM 不可见） */
    private List<String> groups;

    /** MCP 服务器列表 */
    private List<String> mcpServers;

    /** 启用/禁用的技能白名单 */
    private List<String> enabledSkills;

    /** 工具组配置（组名 → 配置映射，预留） */
    private Map<String, Object> groupConfigs;

    public ToolConfig() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getGroups() {
        return groups;
    }

    public void setGroups(List<String> groups) {
        this.groups = groups;
    }

    public List<String> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(List<String> mcpServers) {
        this.mcpServers = mcpServers;
    }

    public List<String> getEnabledSkills() {
        return enabledSkills;
    }

    public void setEnabledSkills(List<String> enabledSkills) {
        this.enabledSkills = enabledSkills;
    }

    public Map<String, Object> getGroupConfigs() {
        return groupConfigs;
    }

    public void setGroupConfigs(Map<String, Object> groupConfigs) {
        this.groupConfigs = groupConfigs;
    }
}