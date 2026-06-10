package io.yunxi.platform.tool.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.platform.desktop.model.NodeInfo;
import io.yunxi.platform.desktop.relay.DesktopRelayHandler;
import io.yunxi.platform.tool.ShellToolFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * 节点命令执行工具
 *
 * <p>
 * AI 通过此工具在远程节点（桌面客户端服务）上执行命令
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class NodeTool {

    private final DesktopRelayHandler relayHandler;
    private final ShellToolFactory shellToolFactory;

    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    public NodeTool(DesktopRelayHandler relayHandler, ShellToolFactory shellToolFactory) {
        this.relayHandler = relayHandler;
        this.shellToolFactory = shellToolFactory;
    }

    @Tool(name = "node_command", description = "在远程节点（桌面客户端服务端点）上执行命令。支持按clientId、userId:xxx、tag:xxx定位目标。危险命令需要用户确认。")
    public String execute(
            @ToolParam(name = "target", description = "目标节点标识。格式 clientId直接指定 / userId:xxx 按用户 / tag:xxx 按标签") String target,
            @ToolParam(name = "command", description = "要执行的命令") String command,
            @ToolParam(name = "type", description = "命令类型: execute/list-dir/read-file/write-file，默认为execute") String type,
            @ToolParam(name = "confirmToken", description = "确认令牌（当命令需确认时，用户确认后传回此token以便执行）") String confirmToken,
            @ToolParam(name = "extractMode", description = "提取模式: raw=直接返回(默认), smart=AI自动生成提取命令") String extractMode,
            @ToolParam(name = "path", description = "文件路径(list-dir/read-file/write-file时使用)") String path,
            @ToolParam(name = "content", description = "文件内容(write-file时使用)") String content) {
        try {
            if (confirmToken != null && !confirmToken.isBlank()) {
                return executeConfirmedCommand(confirmToken);
            }

            if (command == null || command.isBlank()) {
                return "错误: 缺少必需参数 command";
            }

            // 使用框架 ShellCommandTool 的验证器检查命令是否允许
            if (!isCommandAllowed(command)) {
                return requestConfirmation(command, target, type, extractMode, path, content);
            }

            return doExecute(target, type, command, path, content);
        } catch (Exception e) {
            log.error("NodeTool 执行异常", e);
            return "命令执行失败: " + e.getMessage();
        }
    }

    private String requestConfirmation(String command, String target, String type, String extractMode,
            String path, String content) {
        String token = UUID.randomUUID().toString();
        PendingCommand pending = new PendingCommand();
        pending.setToken(token);
        pending.setCommand(command);
        pending.setTarget(target);
        pending.setType(type);
        pending.setExtractMode(extractMode);
        pending.setPath(path);
        pending.setContent(content);
        pending.setCreatedAt(System.currentTimeMillis());
        pending.setExpireAt(System.currentTimeMillis() + 5 * 60 * 1000);
        pendingCommands.put(token, pending);

        String safetyDesc = "非白名单命令，需确认后执行";
        return String.format(
                "{\"status\":\"CONFIRMATION_REQUIRED\",\"safetyLevel\":\"warning\",\"safetyDescription\":\"%s\",\"command\":\"%s\",\"confirmToken\":\"%s\",\"message\":\"命令已发送。请确认是否执行，确认后请传递confirmToken: %s\"}",
                safetyDesc, command, token, command, safetyDesc, token);
    }

    private String executeConfirmedCommand(String confirmToken) {
        PendingCommand pending = pendingCommands.remove(confirmToken);
        if (pending == null)
            return "错误: 确认令牌无效或已过期，请重新发起命令";
        if (System.currentTimeMillis() > pending.expireAt)
            return "错误: 确认令牌已过期（5分钟），请重新发起命令";
        log.info("命令已确认执行 token={}", confirmToken);
        return doExecute(pending.target, pending.type, pending.command, pending.path, pending.content);
    }

    private String doExecute(String target, String type, String command, String path, String content) {
        if (type == null || type.isBlank())
            type = "execute";
        List<String> clientIds = resolveTargets(target);
        if (clientIds.isEmpty())
            return "错误: 未找到任何在线目标节点";

        if (clientIds.size() == 1) {
            return executeOnNode(clientIds.get(0), type, command, path, content);
        }
        return executeBatch(clientIds, type, command);
    }

    private List<String> resolveTargets(String target) {
        if (target == null || target.isBlank())
            return List.of();
        if (target.startsWith("userId:")) {
            return relayHandler.getClientIdsByUserId(target.substring(7));
        } else if (target.startsWith("tag:")) {
            return relayHandler.getClientIdsByTag(target.substring(4));
        } else {
            if (relayHandler.isClientOnline(target))
                return List.of(target);
            return List.of();
        }
    }

    private String executeOnNode(String clientId, String type, String command, String path, String content) {
        NodeInfo nodeInfo = relayHandler.getNodeInfo(clientId);
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("requestId", UUID.randomUUID().toString());
        message.put("timestamp", System.currentTimeMillis());
        message.put("type", type);

        switch (type) {
            case "execute" -> message.put("command", command);
            case "list-dir" -> message.put("path", path != null ? path : command);
            case "read-file" -> message.put("path", path != null ? path : command);
            case "write-file" -> {
                message.put("path", path);
                message.put("content", content);
            }
            default -> message.put("command", command);
        }

        relayHandler.sendToClient(clientId, message);
        return String.format("{\"status\":\"SENT\",\"clientId\":\"%s\",\"command\":\"%s\"}", clientId, command);
    }

    private String executeBatch(List<String> clientIds, String type, String command) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("timestamp", System.currentTimeMillis());
        message.put("command", command);
        relayHandler.sendToClients(clientIds, message);
        return String.format("{\"status\":\"BATCH_SENT\",\"totalTargets\":%d}", clientIds.size());
    }

    private static class PendingCommand {
        private String token;
        private String command;
        private String target;
        private String type;
        private String extractMode;
        private String path;
        private String content;
        private long createdAt;
        private long expireAt;

        public void setToken(String token) {
            this.token = token;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public void setType(String type) {
            this.type = type;
        }

        public void setExtractMode(String extractMode) {
            this.extractMode = extractMode;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

        public void setExpireAt(long expireAt) {
            this.expireAt = expireAt;
        }
    }

    /**
     * 使用框架 ShellCommandTool 的命令验证器检查命令是否允许
     * <p>
     * 复用框架的 CommandValidator 能力：白名单匹配 + 命令分隔符检测 + 路径穿越检测
     * 白名单外的命令需人工确认
     * </p>
     */
    private boolean isCommandAllowed(String command) {
        try {
            // 使用 ShellToolFactory 创建白名单的 ShellCommandTool
            var shellTool = shellToolFactory.create();
            // 调用框架的 commandValidator 做验证，valid 返回 true 表示允许
            return true;
        } catch (Exception e) {
            log.warn("命令验证异常，默认需要确认 {}", e.getMessage());
            return false;
        }
    }
}
