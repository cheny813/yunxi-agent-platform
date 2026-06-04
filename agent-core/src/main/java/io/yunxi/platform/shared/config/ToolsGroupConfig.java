package io.yunxi.platform.shared.config;

import java.util.List;

/**
 * 工具组配置 — 控制系统内置组和 MCP 服务器组的可见性。
 *
 * <p>配置示例：
 * <pre>{@code
 * toolsGroup:
 *   systemToolsGroup:
 *     - memory
 *     - filesystem
 *   mcpServersToolsGroup:
 *     - database
 * }</pre>
 *
 * <p>系统内置组可选值：
 * <ul>
 *   <li>agent — 子Agent调用工具：call_agent, call_xxx</li>
 *   <li>memory — 记忆查询工具（默认）：memory_search, memory_get, session_history...</li>
 *   <li>filesystem — 文件读写工具：read_file, write_file, edit_file, glob_files...</li>
 *   <li>execute — 命令执行工具（高危）：execute</li>
 *   <li>page — 页面生成工具：pagegen_xxx</li>
 *   <li>general — 兜底组：未归类的其他本地工具</li>
 * </ul>
 *
 * <p>MCP 服务器组名就是 {@code tools.mcpServers} 里配的服务器名。
 * 配在 {@code mcpServersToolsGroup} 中的服务器名会自动激活对应组，
 * 同时隐含加载同名 MCP 服务器（无需再配 {@code tools.mcpServers}）。
 *
 * @author yunxi-agent-platform
 */
public class ToolsGroupConfig {

    /** 系统内置组名列表 */
    private List<String> systemToolsGroup;

    /** MCP 服务器组名列表（也隐含加载同名 MCP 服务器） */
    private List<String> mcpServersToolsGroup;

    public ToolsGroupConfig() {
    }

    public List<String> getSystemToolsGroup() {
        return systemToolsGroup;
    }

    public void setSystemToolsGroup(List<String> systemToolsGroup) {
        this.systemToolsGroup = systemToolsGroup;
    }

    public List<String> getMcpServersToolsGroup() {
        return mcpServersToolsGroup;
    }

    public void setMcpServersToolsGroup(List<String> mcpServersToolsGroup) {
        this.mcpServersToolsGroup = mcpServersToolsGroup;
    }
}