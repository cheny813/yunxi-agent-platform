package io.yunxi.platform.framework.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册表
 * 负责动态管理 MCP 服务器工具的注册和刷新
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class McpToolRegistry {

    /** MCP 客户端服务 */
    private final McpClientService mcpClientService;
    /** MCP 工具工厂 */
    private final McpToolFactory mcpToolFactory;
    /** AgentScope 核心配置属性（提供 mcpServers） */
    private final AgentscopeCoreProperties properties;

    // 存储 Agent 名称到 Toolkit 的映射
    private final Map<String, Toolkit> agentToolkits = new ConcurrentHashMap<>();

    // 存储 MCP 服务器名称到工具列表的缓存
    private final Map<String, List<Map<String, Object>>> serverToolsCache = new ConcurrentHashMap<>();

    // 记录每个 MCP 服务器最后的刷新时间
    private final Map<String, Long> serverRefreshTime = new ConcurrentHashMap<>();

    // 记录每个 MCP 服务器的连接状态
    private final Map<String, Boolean> serverConnected = new ConcurrentHashMap<>();

    /**
     * 构造函数
     *
     * @param mcpClientService MCP 客户端服务
     * @param mcpToolFactory   MCP 工具工厂
     * @param properties       AgentScope 核心配置属性
     * @param objectMapper     JSON 序列化工具
     */
    public McpToolRegistry(
            McpClientService mcpClientService,
            McpToolFactory mcpToolFactory,
            AgentscopeCoreProperties properties,
            ObjectMapper objectMapper) {
        this.mcpClientService = mcpClientService;
        this.mcpToolFactory = mcpToolFactory;
        this.properties = properties;
    }

    /**
     * 注册 Agent 的 Toolkit
     * 
     * @param agentName Agent 名称
     * @param toolkit   Toolkit 实例
     */
    public void registerAgentToolkit(String agentName, Toolkit toolkit) {
        agentToolkits.put(agentName, toolkit);
        log.info("已注册 Agent Toolkit: {}", agentName);
    }

    /**
     * 为指定 Agent 加载并注册 MCP 工具
     * 
     * @param agentName   Agent 名称
     * @param serverNames MCP 服务器名称列表
     */
    public void loadAndRegisterMcpTools(String agentName, List<String> serverNames) {
        Toolkit toolkit = agentToolkits.get(agentName);
        if (toolkit == null) {
            log.warn("未找到 Agent [{}] 的 Toolkit", agentName);
            return;
        }

        if (serverNames == null || serverNames.isEmpty()) {
            return;
        }

        log.info("为 Agent [{}] 加载 MCP 工具，服务器列表: {}", agentName, serverNames);

        // 为每个 MCP 服务器创建工具组（必须在注册工具之前）
        for (String serverName : serverNames) {
            ensureToolGroup(toolkit, serverName);
        }

        for (String serverName : serverNames) {
            loadMcpToolsForServer(serverName);
            registerMcpToolsToAgent(agentName, toolkit, serverName);
        }

        log.info("Agent [{}] 已注册 {} 个MCP工具", agentName, toolkit.getToolNames().size());
    }

    /**
     * 确保工具组存在（幂等操作）
     * <p>
     * 在注册工具之前调用，将 MCP 服务器的工具归入对应组。
     * 分组后可通过 toolkit.updateToolGroups() 动态激活/停用，
     * 实现按需发送工具 schema 给 LLM，减少 token 消耗。
     * </p>
     *
     * @param toolkit    工具包
     * @param serverName MCP 服务器名称（同时作为组名）
     */
    private void ensureToolGroup(Toolkit toolkit, String serverName) {
        try {
            toolkit.createToolGroup(serverName, serverName + " MCP 服务工具", true);
            log.debug("创建 MCP 工具组: {}", serverName);
        } catch (Exception e) {
            // 组已存在时忽略（服务器重连场景）
            log.debug("MCP 工具组已存在或创建失败: {} - {}", serverName, e.getMessage());
        }
    }

    /**
     * 从 MCP 服务器加载工具列表
     * 
     * @param serverName MCP 服务器名称
     * @return 工具列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadMcpToolsForServer(String serverName) {
        Map<String, AgentscopeCoreProperties.McpServerConfig> servers = properties.getMcpServers();
        if (servers == null) {
            log.warn("MCP服务器配置为空");
            return Collections.emptyList();
        }
        AgentscopeCoreProperties.McpServerConfig serverConfig = servers.get(serverName);
        if (serverConfig == null || !serverConfig.isEnabled()) {
            log.warn("MCP服务器配置未找到或未启用: {}", serverName);
            return Collections.emptyList();
        }

        try {
            log.info("正在从 MCP 服务器 [{}] 获取工具列表...", serverName);
            List<Map<String, Object>> tools = mcpClientService.listTools(serverName);

            if (tools != null && !tools.isEmpty()) {

                // 更新缓存
                serverToolsCache.put(serverName, tools);
                serverRefreshTime.put(serverName, System.currentTimeMillis());
                serverConnected.put(serverName, true);

                log.info("MCP 服务器 [{}] 加载成功，工具数量: {}, 工具列表: {}",
                        serverName, tools.size(),
                        tools.stream().map(t -> t.get("name")).toList());

                return tools;
            }
        } catch (Exception e) {
            serverConnected.put(serverName, false);
            log.error("从 MCP 服务器 [{}] 获取工具列表失败: {}", serverName, e.getMessage());
            log.debug("详细错误: ", e);
        }

        return Collections.emptyList();
    }

    /**
     * 将 MCP 服务器的工具注册到 Agent 的 Toolkit
     * 
     * @param agentName  Agent 名称
     * @param toolkit    Toolkit 实例
     * @param serverName MCP 服务器名称
     */
    public void registerMcpToolsToAgent(String agentName, Toolkit toolkit, String serverName) {
        Map<String, AgentscopeCoreProperties.McpServerConfig> servers = properties.getMcpServers();
        if (servers == null) {
            return;
        }
        AgentscopeCoreProperties.McpServerConfig serverConfig = servers.get(serverName);
        if (serverConfig == null || !serverConfig.isEnabled()) {
            return;
        }

        List<Map<String, Object>> tools = serverToolsCache.get(serverName);
        if (tools == null || tools.isEmpty()) {
            log.warn("MCP 服务器 [{}] 没有缓存的工具，跳过注册", serverName);
            return;
        }

        log.info("为 Agent [{}] 注册 MCP 服务器 [{}] 的工具", agentName, serverName);

        // 先移除该服务器之前的工具（前缀为 serverName_）
        toolkit.getToolNames().stream()
                .filter(name -> name.startsWith(serverName + "_"))
                .forEach(toolkit::removeTool);

        // 注册新工具
        for (Map<String, Object> tool : tools) {
            String toolName = (String) tool.get("name");
            String toolDesc = (String) tool.get("description");
            @SuppressWarnings("unchecked")
            Map<String, Object> inputSchema = (Map<String, Object>) tool.get("inputSchema");

            // 验证工具信息的完整性，确保大模型能正确理解工具
            if (toolName == null || toolName.isEmpty()) {
                log.warn("跳过无效工具：缺少工具名称, tool={}", tool);
                continue;
            }

            // 增强描述：添加服务器名称前缀，帮助大模型理解工具来源
            String enhancedDesc = String.format("[%s] %s", serverName,
                    toolDesc != null ? toolDesc : "No description provided");

            AgentTool agentTool = mcpToolFactory.createTool(
                    serverName,
                    serverConfig,
                    toolName,
                    enhancedDesc,
                    inputSchema);

            // 使用 fluent API 将 MCP 工具注册到对应服务器名称的工具组
            // 分组后可通过 toolkit.updateToolGroups() 动态激活/停用
            toolkit.registration()
                    .agentTool(agentTool)
                    .group(serverName)
                    .apply();

            // 记录工具注册详情，便于调试
            boolean hasSchema = inputSchema != null && !inputSchema.isEmpty();
            log.info("已注册MCP工具: Agent={}, Server={}, Tool={}, HasSchema={}",
                    agentName, serverName, toolName, hasSchema);
        }
    }

    /**
     * 刷新指定 MCP 服务器的工具
     * 
     * @param serverName MCP 服务器名称
     * @return 刷新是否成功
     */
    public boolean refreshMcpServer(String serverName) {
        log.info("刷新 MCP 服务器 [{}] 的工具...", serverName);

        List<Map<String, Object>> tools = loadMcpToolsForServer(serverName);
        if (tools.isEmpty()) {
            log.warn("MCP 服务器 [{}] 刷新失败，工具列表为空", serverName);
            return false;
        }

        // 为所有使用该服务器的 Agent 重新注册工具
        int successCount = 0;
        for (Map.Entry<String, Toolkit> entry : agentToolkits.entrySet()) {
            String agentName = entry.getKey();
            Toolkit toolkit = entry.getValue();

            try {
                registerMcpToolsToAgent(agentName, toolkit, serverName);
                successCount++;
                log.info("已为 Agent [{}] 刷新 MCP 服务器 [{}] 的工具", agentName, serverName);
            } catch (Exception e) {
                log.error("为 Agent [{}] 刷新 MCP 服务器 [{}] 失败: {}",
                        agentName, serverName, e.getMessage());
            }
        }

        log.info("MCP 服务器 [{}] 刷新完成，成功更新 {}/{} 个 Agent",
                serverName, successCount, agentToolkits.size());

        return successCount > 0;
    }

    /**
     * 刷新所有 MCP 服务器的工具
     * 
     * @return 成功刷新的服务器数量
     */
    public int refreshAllMcpServers() {
        log.info("开始刷新所有 MCP 服务器的工具...");

        Map<String, AgentscopeCoreProperties.McpServerConfig> servers = properties.getMcpServers();
        if (servers == null || servers.isEmpty()) {
            log.warn("没有配置 MCP 服务器");
            return 0;
        }

        int successCount = 0;
        for (String serverName : servers.keySet()) {
            if (refreshMcpServer(serverName)) {
                successCount++;
            }
        }

        log.info("所有 MCP 服务器刷新完成，成功: {}/{}", successCount, servers.size());
        return successCount;
    }

    /**
     * 定时任务：每 30 秒检查一次未连接的 MCP 服务器
     */
    @Scheduled(fixedRate = 30000)
    public void checkDisconnectedServers() {
        for (Map.Entry<String, Boolean> entry : serverConnected.entrySet()) {
            String serverName = entry.getKey();
            Boolean connected = entry.getValue();

            if (!connected) {
                log.info("检测到 MCP 服务器 [{}] 未连接，尝试重新连接...", serverName);
                refreshMcpServer(serverName);
            }
        }
    }

    /**
     * 获取所有 MCP 服务器的状态
     * 
     * @return 服务器状态列表
     */
    public Map<String, Object> getServerStatus() {
        Map<String, Object> status = new HashMap<>();
        Map<String, AgentscopeCoreProperties.McpServerConfig> servers = properties.getMcpServers();
        int totalServers = servers != null ? servers.size() : 0;
        status.put("totalServers", totalServers);
        status.put("connectedServers", serverConnected.values().stream().filter(c -> c).count());
        status.put("servers", new HashMap<>());

        @SuppressWarnings("unchecked")
        Map<String, Object> serversDetail = (Map<String, Object>) status.get("servers");

        if (servers != null) {
            for (String serverName : servers.keySet()) {
                Map<String, Object> serverStatus = new HashMap<>();
                serverStatus.put("connected", serverConnected.getOrDefault(serverName, false));
                serverStatus.put("toolCount",
                        serverToolsCache.getOrDefault(serverName, Collections.emptyList()).size());
                serverStatus.put("lastRefreshTime", serverRefreshTime.get(serverName));
                serversDetail.put(serverName, serverStatus);
            }
        }

        return status;
    }

    /**
     * 获取缓存的工具列表
     * 
     * @param serverName MCP 服务器名称
     * @return 工具列表
     */
    public List<Map<String, Object>> getCachedTools(String serverName) {
        return serverToolsCache.getOrDefault(serverName, Collections.emptyList());
    }

    /**
     * 获取所有已注册工具的元信息（用于调试大模型工具发现）
     * 展示大模型当前能"看到"的所有工具及其参数定义
     * 
     * @return 工具元信息列表
     */
    public List<Map<String, Object>> getAllRegisteredToolMetadata() {
        List<Map<String, Object>> metadataList = new ArrayList<>();

        for (Map.Entry<String, Toolkit> entry : agentToolkits.entrySet()) {
            String agentName = entry.getKey();
            Toolkit toolkit = entry.getValue();

            for (String toolFullName : toolkit.getToolNames()) {
                AgentTool tool = toolkit.getTool(toolFullName);
                if (tool != null) {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("agentName", agentName);
                    metadata.put("toolFullName", toolFullName);
                    metadata.put("toolName", tool.getName());
                    metadata.put("description", tool.getDescription());
                    metadata.put("parameters", tool.getParameters());

                    // 提取参数的必需字段和可选字段
                    Map<String, Object> params = tool.getParameters();
                    @SuppressWarnings("unchecked")
                    List<String> required = params != null ? (List<String>) params.get("required") : List.of();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> properties = params != null ? (Map<String, Object>) params.get("properties")
                            : new HashMap<>();

                    metadata.put("requiredParams", required);
                    metadata.put("optionalParams", properties.keySet().stream()
                            .filter(k -> !required.contains(k))
                            .toList());

                    metadataList.add(metadata);
                }
            }
        }

        return metadataList;
    }

    /**
     * 手动设置服务器连接状态
     * 
     * @param serverName 服务器名称
     * @param connected  是否连接
     */
    public void setServerConnected(String serverName, boolean connected) {
        serverConnected.put(serverName, connected);
    }
}
