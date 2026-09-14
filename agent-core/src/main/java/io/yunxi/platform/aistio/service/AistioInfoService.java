package io.yunxi.platform.aistio.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.yunxi.platform.agent.mcp.McpConfigStore;
import io.yunxi.platform.agent.mcp.McpServerEntry;
import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.aistio.dto.DataPlaneInfo;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import lombok.RequiredArgsConstructor;

/**
 * 组装 {@link DataPlaneInfo}（/info 与注册 payload 共用）。
 *
 * <p>能力（capabilities）按配置开关声明；工具（tools）取自已启用的 MCP 服务名集合。</p>
 */
@Component
@RequiredArgsConstructor
public class AistioInfoService {

    private static final Logger log = LoggerFactory.getLogger(AistioInfoService.class);

    private final AistioIntegrationProperties properties;
    private final McpConfigStore mcpConfigStore;
    private final AgentDefinitionLoader definitionLoader;

    /**
     * 构建数据面信息。
     *
     * @return 数据面信息
     */
    public DataPlaneInfo buildInfo() {
        DataPlaneInfo info = new DataPlaneInfo();
        info.setRuntime("yunxi-agent-platform");
        info.setVersion("2.0.3");
        info.setContractLevel(properties.getContractLevel());
        info.setNamespace(properties.getNamespace());
        info.setName(properties.getAgentName());
        info.setSessionAffinity("sticky");

        List<String> capabilities = new ArrayList<>();
        capabilities.add("session-reporting");
        if (properties.isAdvertiseContext()) {
            capabilities.add("context-query");
        }
        capabilities.add("message-query");
        if (properties.isAdvertiseSubagents()) {
            capabilities.add("subagent-inventory");
        }
        if (properties.isAdvertiseWorkspaces()) {
            capabilities.add("workspace-inventory");
        }
        // Phase 3 高级治理 / 协作能力（contractLevel >= 3 默认开启）
        if (properties.getContractLevel() >= 3) {
            capabilities.add("task-query");
            capabilities.add("plan-mode");
            capabilities.add("team-coordination");
        }
        if (properties.isCommandEnabled()) {
            capabilities.add("session-command");
        }
        info.setCapabilities(capabilities);

        List<String> tools = new ArrayList<>();
        try {
            for (McpServerEntry entry : mcpConfigStore.listServerEntries()) {
                if (entry.isEnabled()) {
                    tools.add(entry.getName());
                }
            }
        } catch (Exception e) {
            log.warn("读取 MCP 服务清单失败，tools 将为空: {}", e.getMessage());
        }
        info.setTools(tools);

        // 仅用于日志观测，不影响契约
        log.debug("构建 aistio DataPlaneInfo: capabilities={}, tools={}", capabilities, tools);
        return info;
    }
}
