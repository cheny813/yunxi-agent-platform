package io.yunxi.platform.shared.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具门控配置
 *
 * <p>控制哪些工具需要人类审批才能执行。当 LLM 决定调用配置的危险工具时，
 * ToolGateHook 会暂停 Agent 执行，等待人类确认。
 *
 * <pre>
 * hitl:
 *   toolGate:
 *     enabled: true
 *     tools: ["delete_file", "exec_command"]
 *     message: "工具 [{tool}] 需要人工确认后才能执行"
 * </pre>
 *
 * @author yunxi-platform
 */
public class ToolGateConfig {

    /** 是否启用工具门控 */
    private boolean enabled = false;

    /** 需要审批的工具名称列表 */
    private List<String> tools = new ArrayList<>();

    /** 展示给用户的审批提示模板，{tool} 会被替换为实际工具名 */
    private String message = "工具 [{tool}] 需要人工确认后才能执行";

    public ToolGateConfig() {}

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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}