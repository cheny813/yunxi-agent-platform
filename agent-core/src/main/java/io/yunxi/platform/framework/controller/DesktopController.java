package io.yunxi.platform.framework.controller;

import io.yunxi.platform.framework.controller.DesktopRelayHandler.CommandResultEvent;
import io.yunxi.platform.framework.profile.NodeProfile;
import io.yunxi.platform.framework.profile.NodeProfileService;
import io.yunxi.platform.framework.security.NodeAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 桌面客户端管理控制器
 *
 * 提供 AI 与桌面客户端通信的 REST API
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop")
public class DesktopController {

    /** 桌面中继处理器 */
    private final DesktopRelayHandler desktopRelayHandler;
    /** 节点审计服务 */
    private final NodeAuditService auditService;
    /** 节点画像服务 */
    private final NodeProfileService profileService;

    /** 命令结果存储: requestId -> CompletableFuture */
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingResults = new ConcurrentHashMap<>();

    /** 命令结果缓存: requestId -> result */
    private final Map<String, CommandResult> resultCache = new ConcurrentHashMap<>();

    /**
     * 构造桌面客户端管理控制器
     *
     * @param desktopRelayHandler 桌面中继处理器
     * @param auditService        节点审计服务
     * @param profileService      节点画像服务
     */
    public DesktopController(DesktopRelayHandler desktopRelayHandler, NodeAuditService auditService,
                             NodeProfileService profileService) {
        this.desktopRelayHandler = desktopRelayHandler;
        this.auditService = auditService;
        this.profileService = profileService;
    }

    /**
     * 监听命令结果事件
     */
    @EventListener
    public void handleCommandResultEvent(CommandResultEvent event) {
        CompletableFuture<Map<String, Object>> future = pendingResults.get(event.getRequestId());
        if (future != null) {
            future.complete(event.getResult());
        }
    }

    /**
     * 获取所有在线的桌面客户端
     */
    @GetMapping("/clients")
    public ResponseEntity<Map<String, Object>> getClients() {
        Map<String, NodeInfo> clients = desktopRelayHandler.getOnlineClients();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", clients.size());
        result.put("clients", clients.values());

        return ResponseEntity.ok(result);
    }

    /**
     * 向指定桌面客户端发送命令（同步等待结果）
     * 
     * AI 调用此 API 将命令转发给桌面客户端执行，并等待执行结果
     */
    @PostMapping("/command/{clientId}")
    public ResponseEntity<Map<String, Object>> sendCommandSync(
            @PathVariable String clientId,
            @RequestBody CommandRequest request) {

        // 生成请求ID
        String requestId = request.getRequestId() != null ? request.getRequestId() : UUID.randomUUID().toString();

        log.info("AI 发送同步命令到客户端 {}: type={}, requestId={}", clientId, request.getType(), requestId);

        // 检查客户端是否在线
        if (!desktopRelayHandler.isClientOnline(clientId)) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "CLIENT_OFFLINE");
            error.put("message", "客户端不在线或已断开连接");
            return ResponseEntity.status(503).body(error);
        }

        // 构建转发消息
        Map<String, Object> message = buildMessage(request, requestId);

        // 创建CompletableFuture用于等待结果
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingResults.put(requestId, future);

        // 发送消息到客户端
        desktopRelayHandler.sendToClient(clientId, message);

        try {
            // 等待结果，默认超时30秒
            Map<String, Object> result = future.get(30, TimeUnit.SECONDS);

            // 缓存结果（保留1分钟）
            resultCache.put(requestId, new CommandResult(result, System.currentTimeMillis() + 60000));

            // 清理pending
            pendingResults.remove(requestId);

            Map<String, Object> response = new HashMap<>(result);
            response.put("success", "success".equals(result.get("status")));
            response.put("requestId", requestId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            pendingResults.remove(requestId);

            log.error("命令执行超时或失败: requestId={}, error={}", requestId, e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "TIMEOUT");
            error.put("message", "命令执行超时: " + e.getMessage());
            error.put("requestId", requestId);

            return ResponseEntity.status(504).body(error);
        }
    }

    /**
     * 向指定桌面客户端发送命令（异步）
     * 
     * 命令发送后立即返回，AI 可以通过 /result/{requestId} 获取结果
     */
    @PostMapping("/command/async/{clientId}")
    public ResponseEntity<Map<String, Object>> sendCommandAsync(
            @PathVariable String clientId,
            @RequestBody CommandRequest request) {

        String requestId = request.getRequestId() != null ? request.getRequestId() : UUID.randomUUID().toString();

        log.info("AI 发送异步命令到客户端 {}: type={}, requestId={}", clientId, request.getType(), requestId);

        if (!desktopRelayHandler.isClientOnline(clientId)) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "CLIENT_OFFLINE");
            error.put("message", "客户端不在线");
            return ResponseEntity.status(503).body(error);
        }

        Map<String, Object> message = buildMessage(request, requestId);
        desktopRelayHandler.sendToClient(clientId, message);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "命令已发送");
        response.put("requestId", requestId);
        response.put("clientId", clientId);
        response.put("pollUrl", "/api/desktop/result/" + requestId);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取命令执行结果（轮询）
     */
    @GetMapping("/result/{requestId}")
    public ResponseEntity<Map<String, Object>> getResult(@PathVariable String requestId) {
        // 检查pending结果
        CompletableFuture<Map<String, Object>> future = pendingResults.get(requestId);
        if (future != null) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    Map<String, Object> result = future.getNow(null);
                    pendingResults.remove(requestId);
                    if (result != null) {
                        resultCache.put(requestId, new CommandResult(result, System.currentTimeMillis() + 60000));
                    }
                    return ResponseEntity.ok(buildResultResponse(requestId, result));
                } catch (Exception e) {
                    pendingResults.remove(requestId);
                }
            } else if (future.isCompletedExceptionally()) {
                pendingResults.remove(requestId);
                return ResponseEntity.ok(buildErrorResponse(requestId, "执行失败"));
            }

            // 仍在执行中
            Map<String, Object> response = new HashMap<>();
            response.put("status", "PENDING");
            response.put("requestId", requestId);
            return ResponseEntity.ok(response);
        }

        // 检查缓存结果
        CommandResult cached = resultCache.get(requestId);
        if (cached != null) {
            if (cached.isExpired()) {
                resultCache.remove(requestId);
                return ResponseEntity.ok(buildErrorResponse(requestId, "结果已过期"));
            }
            return ResponseEntity.ok(buildResultResponse(requestId, cached.getResult()));
        }

        // 未找到
        return ResponseEntity.ok(buildErrorResponse(requestId, "请求不存在或已过期"));
    }

    /**
     * 广播命令到所有桌面客户端
     */
    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(@RequestBody CommandRequest request) {
        log.info("AI 广播命令: type={}", request.getType());

        String requestId = request.getRequestId() != null ? request.getRequestId() : UUID.randomUUID().toString();

        Map<String, Object> message = new HashMap<>();
        message.put("type", request.getType());
        message.put("requestId", requestId);
        message.put("timestamp", System.currentTimeMillis());

        if (request.getCommand() != null) {
            message.put("command", request.getCommand());
            message.put("args", request.getArgs() != null ? request.getArgs() : new String[] {});
        }

        desktopRelayHandler.broadcast(message);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "广播完成");
        response.put("requestId", requestId);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取客户端连接状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("success", true);
        status.put("clientCount", desktopRelayHandler.getClientCount());
        status.put("onlineClients", desktopRelayHandler.getOnlineClients().keySet());
        status.put("pendingRequests", pendingResults.size());

        return ResponseEntity.ok(status);
    }

    /**
     * 按 userId 查询关联的在线节点
     */
    @GetMapping("/clients/user/{userId}")
    public ResponseEntity<Map<String, Object>> getClientsByUser(@PathVariable String userId) {
        List<String> clientIds = desktopRelayHandler.getClientIdsByUserId(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("userId", userId);
        result.put("count", clientIds.size());
        result.put("clients", clientIds.stream()
                .map(id -> desktopRelayHandler.getNodeInfo(id))
                .toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 按 tag 查询关联的在线节点
     */
    @GetMapping("/clients/tag/{tag}")
    public ResponseEntity<Map<String, Object>> getClientsByTag(@PathVariable String tag) {
        List<String> clientIds = desktopRelayHandler.getClientIdsByTag(tag);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("tag", tag);
        result.put("count", clientIds.size());
        result.put("clients", clientIds.stream()
                .map(id -> desktopRelayHandler.getNodeInfo(id))
                .toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 批量向多个节点发送命令
     *
     * <p>请求体中 targets 为目标标识列表（支持 clientId、userId:xxx、tag:xxx 格式）。</p>
     */
    @PostMapping("/command/batch")
    public ResponseEntity<Map<String, Object>> sendBatchCommand(@RequestBody BatchCommandRequest request) {
        List<String> targetClientIds = resolveTargets(request.getTargets());

        if (targetClientIds.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "NO_TARGETS");
            error.put("message", "未找到任何在线目标节点");
            return ResponseEntity.status(404).body(error);
        }

        Map<String, Object> message = new HashMap<>();
        message.put("type", request.getType());
        message.put("timestamp", System.currentTimeMillis());

        if (request.getCommand() != null) {
            message.put("command", request.getCommand());
        }
        if (request.getArgs() != null) {
            message.put("args", request.getArgs());
        }

        Map<String, String> results = desktopRelayHandler.sendToClients(targetClientIds, message);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("totalTargets", targetClientIds.size());
        response.put("results", results);

        return ResponseEntity.ok(response);
    }

    /**
     * 解析目标标识列表
     *
     * <p>支持格式：
     * - 纯 clientId: 直接使用
     * - "userId:xxx": 按用户 ID 查询关联节点
     * - "tag:xxx": 按标签查询关联节点</p>
     */
    private List<String> resolveTargets(List<String> targets) {
        return targets.stream()
                .flatMap(target -> {
                    if (target.startsWith("userId:")) {
                        String userId = target.substring(7);
                        return desktopRelayHandler.getClientIdsByUserId(userId).stream();
                    } else if (target.startsWith("tag:")) {
                        String tag = target.substring(4);
                        return desktopRelayHandler.getClientIdsByTag(tag).stream();
                    } else {
                        return desktopRelayHandler.isClientOnline(target)
                                ? java.util.stream.Stream.of(target)
                                : java.util.stream.Stream.empty();
                    }
                })
                .distinct()
                .toList();
    }

    /**
     * 查询命令审计日志
     */
    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> getAuditLog(
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String targetClientId,
            @RequestParam(required = false) String safetyLevel,
            @RequestParam(defaultValue = "50") int limit) {

        List<Map<String, Object>> logs = auditService.query(operatorId, targetClientId, safetyLevel, limit);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", logs.size());
        result.put("logs", logs);

        return ResponseEntity.ok(result);
    }

    /**
     * 查询指定节点的画像
     */
    @GetMapping("/profile/{clientId}")
    public ResponseEntity<Map<String, Object>> getNodeProfile(@PathVariable String clientId) {
        NodeProfile profile = profileService.getByClientId(clientId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("clientId", clientId);
        result.put("profile", profile);

        return ResponseEntity.ok(result);
    }

    /**
     * 按标签查询节点画像
     */
    @GetMapping("/profile/tag/{tag}")
    public ResponseEntity<Map<String, Object>> getNodeProfilesByTag(@PathVariable String tag) {
        List<NodeProfile> profiles = profileService.getByTag(tag);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("tag", tag);
        result.put("count", profiles.size());
        result.put("profiles", profiles);

        return ResponseEntity.ok(result);
    }

    /**
     * 按用户查询节点画像
     */
    @GetMapping("/profile/user/{userId}")
    public ResponseEntity<Map<String, Object>> getNodeProfilesByUser(@PathVariable String userId) {
        List<NodeProfile> profiles = profileService.getByUserId(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("userId", userId);
        result.put("count", profiles.size());
        result.put("profiles", profiles);

        return ResponseEntity.ok(result);
    }

    /**
     * 处理桌面客户端返回的结果（供 DesktopRelayHandler 调用）
     */
    public void handleClientResult(String requestId, Map<String, Object> result) {
        CompletableFuture<Map<String, Object>> future = pendingResults.get(requestId);
        if (future != null) {
            future.complete(result);
        }
    }

    private Map<String, Object> buildMessage(CommandRequest request, String requestId) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", request.getType());
        message.put("requestId", requestId);
        message.put("timestamp", System.currentTimeMillis());

        switch (request.getType()) {
            case "execute":
                message.put("command", request.getCommand());
                message.put("args", request.getArgs() != null ? request.getArgs() : new String[] {});
                message.put("cwd", request.getCwd());
                break;

            case "git":
                message.put("operation", request.getOperation());
                message.put("args", request.getArgs() != null ? request.getArgs() : new String[] {});
                break;

            case "list-dir":
                message.put("path", request.getPath());
                break;

            case "read-file":
                message.put("path", request.getPath());
                break;

            case "write-file":
                message.put("path", request.getPath());
                message.put("content", request.getContent());
                break;

            case "ping":
                // 简单心跳
                break;

            default:
                message.put("error", "UNKNOWN_COMMAND_TYPE");
                message.put("message", "未知命令类型: " + request.getType());
        }

        return message;
    }

    private Map<String, Object> buildResultResponse(String requestId, Map<String, Object> result) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "COMPLETED");
        response.put("requestId", requestId);

        if (result != null) {
            response.put("success", "success".equals(result.get("status")));
            response.put("result", result);
        }

        return response;
    }

    private Map<String, Object> buildErrorResponse(String requestId, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ERROR");
        response.put("requestId", requestId);
        response.put("message", message);
        return response;
    }

    /**
     * 命令请求 DTO
     */
    @lombok.Data
    public static class CommandRequest {
        private String type; // 命令类型: execute, git, list-dir, read-file, write-file, ping
        private String requestId; // 请求ID，用于追踪
        private String command; // 执行命令 (execute)
        private String[] args; // 命令参数
        private String cwd; // 工作目录
        private String operation; // Git 操作 (git)
        private String path; // 文件路径
        private String content; // 文件内容 (write-file)
    }

    /**
     * 命令结果封装
     */
    @lombok.Data
    public static class CommandResult {
        private Map<String, Object> result;
        private long expireTime;

        public CommandResult(Map<String, Object> result, long expireTime) {
            this.result = result;
            this.expireTime = expireTime;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }

    /**
     * 批量命令请求 DTO
     */
    @lombok.Data
    public static class BatchCommandRequest {
        /** 目标标识列表（clientId / userId:xxx / tag:xxx） */
        private List<String> targets;
        /** 命令类型 */
        private String type;
        /** 执行命令 */
        private String command;
        /** 命令参数 */
        private String[] args;
    }
}