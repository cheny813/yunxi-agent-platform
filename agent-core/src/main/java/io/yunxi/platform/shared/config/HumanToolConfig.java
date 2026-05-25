package io.yunxi.platform.shared.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 人机协作配置
 *
 * <p>控制哪些工具以 SchemaOnlyTool 模式注册，供 LLM 调用以请求人类参与。
 * 当 LLM 调用这些工具时，AgentScope SDK 的 ToolSuspendException 机制会
 * 自动触发 Agent 挂起，等待外部系统提供工具执行结果。
 *
 * <pre>
 * hitl:
 *   humanTool:
 *     enabled: true
 *     tools: ["ask_user"]
 * </pre>
 *
 * @author yunxi-platform
 */
public class HumanToolConfig {

    /** 是否启用人机协作 */
    private boolean enabled = false;

    /** 注册为 SchemaOnlyTool 的工具名称列表 */
    private List<String> tools = new ArrayList<>();

    public HumanToolConfig() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }
}