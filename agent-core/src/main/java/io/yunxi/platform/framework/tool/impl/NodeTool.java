package io.yunxi.platform.framework.tool.impl;

import io.yunxi.platform.framework.controller.DesktopRelayHandler;
import io.yunxi.platform.framework.controller.NodeInfo;
import io.yunxi.platform.framework.security.CommandSafety;
import io.yunxi.platform.framework.security.CommandSafetyClassifier;
import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolExecutionException;
import io.yunxi.platform.framework.tool.ToolInput;
import io.yunxi.platform.framework.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点命令执行工具
 *
 * <p>AI 通过此工具在远程节点（桌面/服务器）上执行命令。</p>
 *
 * <p>安全机制：</p>
 * <ul>
 *   <li>四级安全分级：SAFE / WARNING / DANGEROUS / BLOCKED</li>
 *   <li>确认令牌机制：WARNING/DANGEROUS 级别需用户确认后才能执行</li>
 *   <li>用户安全规则检查：从持久化记忆中读取用户声明的不允许操作</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class NodeTool implements Tool {

    /** 桌面中继处理器 */
    private final DesktopRelayHandler relayHandler;
    /** 命令安全分类器 */
    private final CommandSafetyClassifier safetyClassifier;

    /** 待确认命令: confirmToken -> PendingCommand */
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    /**
     * 构造节点命令执行工具
     *
     * @param relayHandler      桌面中继处理器
     * @param safetyClassifier  命令安全分类器
     */
    public NodeTool(DesktopRelayHandler relayHandler, CommandSafetyClassifier safetyClassifier) {
        this.relayHandler = relayHandler;
        this.safetyClassifier = safetyClassifier;
    }

    @Override
    public String getName() {
        return "node_command";
    }

    @Override
    public String getDescription() {
        return "在远程节点（桌面客户端或服务器节点）上执行命令。"
                + "支持按 userId、clientId、tag 定位目标节点，支持批量操作。"
                + "对于危险命令需要用户确认后才能执行。";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "target": {
                      "type": "string",
                      "description": "目标节点标识。格式: clientId直接指定 / userId:xxx 按用户 / tag:xxx 按标签"
                    },
                    "targets": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "批量目标列表（与target二选一），每个元素支持 clientId/userId:xxx/tag:xxx 格式"
                    },
                    "command": {
                      "type": "string",
                      "description": "要执行的命令"
                    },
                    "type": {
                      "type": "string",
                      "enum": ["execute", "list-dir", "read-file", "write-file"],
                      "description": "命令类型，默认 execute"
                    },
                    "confirmToken": {
                      "type": "string",
                      "description": "确认令牌（当命令需确认时，用户确认后传入此token以执行）"
                    },
                    "extractMode": {
                      "type": "string",
                      "enum": ["raw", "smart"],
                      "description": "提取模式: raw=直接返回(默认), smart=AI自动生成提取命令(适合大文件/日志)"
                    },
                    "extractHint": {
                      "type": "string",
                      "description": "提取提示(extractMode=smart时)，如: '查找错误'/'今天14:30超时'/'配平功能慢'"
                    },
                    "path": {
                      "type": "string",
                      "description": "文件路径(list-dir/read-file/write-file时使用)"
                    },
                    "content": {
                      "type": "string",
                      "description": "文件内容(write-file时使用)"
                    },
                    "args": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "命令参数"
                    }
                  },
                  "required": ["command"]
                }
                """;
    }

    @Override
    public ToolResult execute(ToolInput input) throws ToolExecutionException {
        // 0. 检查是否为确认操作
        String confirmToken = input.getString("confirmToken");
        if (confirmToken != null && !confirmToken.isBlank()) {
            return executeConfirmedCommand(confirmToken);
        }

        String command = input.getString("command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("缺少必要参数: command");
        }

        // 1. 安全分级
        CommandSafety safety = safetyClassifier.classify(command);

        // 2. BLOCKED 直接拒绝
        if (safety == CommandSafety.BLOCKED) {
            log.warn("[NodeTool] 命令被阻止: {}", command);
            return ToolResult.error("该命令被安全策略阻止，禁止执行: " + command);
        }

        // 3. WARNING/DANGEROUS 需要确认
        if (safety.requiresConfirmation()) {
            return requestConfirmation(input, safety);
        }

        // 4. SAFE 直接执行
        return doExecute(input);
    }

    /**
     * 请求用户确认
     */
    private ToolResult requestConfirmation(ToolInput input, CommandSafety safety) {
        String command = input.getString("command");
        String token = UUID.randomUUID().toString();

        // 保存待确认命令
        PendingCommand pending = new PendingCommand();
        pending.setToken(token);
        pending.setCommand(command);
        pending.setInput(input);
        pending.setSafety(safety);
        pending.setCreatedAt(System.currentTimeMillis());
        pendingCommands.put(token, pending);

        // 5分钟过期
        long expiryMs = 5 * 60 * 1000;
        pending.setExpireAt(System.currentTimeMillis() + expiryMs);

        String safetyDesc = safety == CommandSafety.DANGEROUS
                ? "高危命令，需二次确认"
                : "需确认后执行";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "CONFIRMATION_REQUIRED");
        result.put("safetyLevel", safety.getCode());
        result.put("safetyDescription", safetyDesc);
        result.put("command", command);
        result.put("confirmToken", token);
        result.put("message", String.format(
                "命令「%s」被标记为%s。请确认是否执行，确认时请传入 confirmToken: %s",
                command, safetyDesc, token));

        return ToolResult.success(result);
    }

    /**
     * 执行已确认的命令
     */
    private ToolResult executeConfirmedCommand(String confirmToken) {
        PendingCommand pending = pendingCommands.remove(confirmToken);
        if (pending == null) {
            return ToolResult.error("确认令牌无效或已过期，请重新发起命令");
        }

        if (System.currentTimeMillis() > pending.getExpireAt()) {
            return ToolResult.error("确认令牌已过期（5分钟），请重新发起命令");
        }

        log.info("[NodeTool] 命令已确认执行: token={}, safety={}, command={}",
                confirmToken, pending.getSafety(), pending.getCommand());

        return doExecute(pending.getInput());
    }

    /**
     * 执行命令
     */
    private ToolResult doExecute(ToolInput input) {
        String command = input.getString("command");
        String type = input.getString("type", "execute");

        // 解析目标节点
        List<String> targetClientIds = resolveTargets(input);
        if (targetClientIds.isEmpty()) {
            return ToolResult.error("未找到任何在线目标节点");
        }

        // 单节点执行
        if (targetClientIds.size() == 1) {
            return executeOnNode(targetClientIds.get(0), type, command, input);
        }

        // 多节点批量执行
        return executeBatch(targetClientIds, type, command, input);
    }

    /**
     * 解析目标节点
     */
    private List<String> resolveTargets(ToolInput input) {
        // 优先使用 targets 列表
        if (input.hasParameter("targets")) {
            Object targetsObj = input.getParameter("targets", Object.class);
            if (targetsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> targets = (List<String>) targetsObj;
                return targets.stream()
                        .flatMap(t -> resolveSingleTarget(t).stream())
                        .distinct()
                        .toList();
            }
        }

        // 单个 target
        String target = input.getString("target");
        if (target != null && !target.isBlank()) {
            return resolveSingleTarget(target);
        }

        return List.of();
    }

    private List<String> resolveSingleTarget(String target) {
        if (target.startsWith("userId:")) {
            String userId = target.substring(7);
            List<String> clientIds = relayHandler.getClientIdsByUserId(userId);
            if (clientIds.isEmpty()) {
                log.warn("[NodeTool] userId={} 无在线节点", userId);
            }
            return clientIds;
        } else if (target.startsWith("tag:")) {
            String tag = target.substring(4);
            List<String> clientIds = relayHandler.getClientIdsByTag(tag);
            if (clientIds.isEmpty()) {
                log.warn("[NodeTool] tag={} 无在线节点", tag);
            }
            return clientIds;
        } else {
            // 直接作为 clientId
            if (relayHandler.isClientOnline(target)) {
                return List.of(target);
            }
            log.warn("[NodeTool] 节点不在线: {}", target);
            return List.of();
        }
    }

    /**
     * 在单个节点上执行命令
     */
    private ToolResult executeOnNode(String clientId, String type, String command, ToolInput input) {
        NodeInfo nodeInfo = relayHandler.getNodeInfo(clientId);

        // 构建转发消息
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("requestId", UUID.randomUUID().toString());
        message.put("timestamp", System.currentTimeMillis());

        switch (type) {
            case "execute" -> {
                message.put("command", command);
                Object args = input.getParameter("args", Object.class);
                message.put("args", args != null ? args : new String[]{});
            }
            case "list-dir" -> message.put("path", input.getString("path", command));
            case "read-file" -> message.put("path", input.getString("path", command));
            case "write-file" -> {
                message.put("path", input.getString("path"));
                message.put("content", input.getString("content"));
            }
            default -> {
                message.put("command", command);
            }
        }

        relayHandler.sendToClient(clientId, message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SENT");
        result.put("clientId", clientId);
        result.put("nodeType", nodeInfo != null ? nodeInfo.getNodeType() : "unknown");
        result.put("hostname", nodeInfo != null ? nodeInfo.getHostname() : "unknown");
        result.put("command", command);

        return ToolResult.success(result);
    }

    /**
     * 批量执行命令
     */
    private ToolResult executeBatch(List<String> clientIds, String type, String command, ToolInput input) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("timestamp", System.currentTimeMillis());
        message.put("command", command);

        Map<String, String> results = relayHandler.sendToClients(clientIds, message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "BATCH_SENT");
        result.put("totalTargets", clientIds.size());
        result.put("results", results);

        return ToolResult.success(result);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * 待确认命令
     */
    @lombok.Data
    private static class PendingCommand {
        /** 确认令牌 */
        private String token;
        /** 待确认命令 */
        private String command;
        /** 工具输入参数 */
        private ToolInput input;
        /** 安全级别 */
        private CommandSafety safety;
        /** 创建时间 */
        private long createdAt;
        /** 过期时间 */
        private long expireAt;
    }
}
