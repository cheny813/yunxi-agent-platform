package io.yunxi.platform.desktop.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.agent.workspace.UserWorkspaceService;
import io.yunxi.platform.desktop.model.NodeInfo;
import io.yunxi.platform.agent.profile.NodeProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 桌面 WebSocket 中继处理器
 * <p>
 * 负责管理桌面客户端的 WebSocket 连接，接收客户端命令并转发 AI Agent 响应。
 * </p>
 *
 * <p>
 * 按 userId 和 tag 两个维度进行节点分组。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class DesktopRelayHandler extends TextWebSocketHandler {

    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Spring 事件发布器 */
    private final ApplicationEventPublisher eventPublisher;

    /** 节点画像服务 */
    private final NodeProfileService profileService;

    /** 用户工作空间服务 */
    private final UserWorkspaceService userWorkspaceService;

    /** 在线客户端映射: clientId -> session */
    private final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();

    /** 节点注册表 */
    private final Map<String, NodeInfo> nodeRegistry = new ConcurrentHashMap<>();

    /** userId -> clientId 集合，用于按用户分组 */
    private final Map<String, Set<String>> userNodeMap = new ConcurrentHashMap<>();

    /** tag -> Set<clientId> 映射 */
    private final Map<String, Set<String>> tagNodeMap = new ConcurrentHashMap<>();

    /** 待处理请求: requestId -> clientId */
    private final ConcurrentMap<String, String> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 构造函数
     *
     * @param eventPublisher Spring 事件发布器
     * @param profileService 节点画像服务
     */
    public DesktopRelayHandler(ApplicationEventPublisher eventPublisher, NodeProfileService profileService,
            UserWorkspaceService userWorkspaceService) {
        this.eventPublisher = eventPublisher;
        this.profileService = profileService;
        this.userWorkspaceService = userWorkspaceService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String clientId = getClientId(session);
        if (clientId == null) {
            clientId = "node-" + System.currentTimeMillis();
        }

        clients.put(clientId, session);
        session.getAttributes().put("clientId", clientId);

        log.info("客户端已连接: {}, 当前在线 {}", clientId, clients.size());

        sendMessage(session, Map.of(
                "type", "welcome",
                "clientId", clientId,
                "message", "已连接到中继服务器"));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到客户端消息: {}", payload);

        try {
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            String type = (String) data.get("type");

            switch (type) {
                case "register":
                    handleRegister(session, data);
                    break;
                case "pong":
                    log.debug("收到心跳响应: {}", session.getAttributes().get("clientId"));
                    break;
                case "result":
                    handleCommandResult(data);
                    break;
                case "error":
                    handleCommandError(data);
                    break;
                default:
                    log.warn("未知消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理消息异常: {}", e.getMessage());
        }
    }

    /**
     * 处理注册请求
     *
     * <p>
     * 解析客户端上报的 userId/nodeType/tags/hostname/os/localIp 等信息。
     * </p>
     */
    @SuppressWarnings("unchecked")
    private void handleRegister(WebSocketSession session, Map<String, Object> data) {
        String clientId = (String) data.get("clientId");

        NodeInfo info = new NodeInfo();
        info.setClientId(clientId);
        info.setUserId((String) data.get("userId"));
        info.setNodeType((String) data.getOrDefault("nodeType", "desktop"));
        info.setHostname((String) data.get("hostname"));
        info.setOs((String) data.get("os"));
        info.setLocalIp((String) data.get("localIp"));
        info.setConnectedAt(System.currentTimeMillis());
        info.setLastHeartbeat(System.currentTimeMillis());

        // capabilities 支持 String 或 List 两种格式
        Object capabilities = data.get("capabilities");
        if (capabilities instanceof List) {
            info.setCapabilities(String.join(",", (List<String>) capabilities));
        } else if (capabilities instanceof String) {
            info.setCapabilities((String) capabilities);
        }

        // tags 支持 String 或 List 两种格式
        Object tags = data.get("tags");
        if (tags instanceof List) {
            info.setTags((List<String>) tags);
        } else if (tags instanceof String) {
            String tagStr = (String) tags;
            info.setTags(tagStr.isBlank() ? List.of() : Arrays.asList(tagStr.split(",")));
        } else {
            info.setTags(List.of());
        }

        nodeRegistry.put(clientId, info);

        // 注册 userId -> clientId 映射
        if (info.getUserId() != null && !info.getUserId().isBlank()) {
            userNodeMap.computeIfAbsent(info.getUserId(), k -> ConcurrentHashMap.newKeySet())
                    .add(clientId);
        }

        // 注册 tag -> clientId 映射
        if (info.getTags() != null) {
            for (String tag : info.getTags()) {
                if (tag != null && !tag.isBlank()) {
                    tagNodeMap.computeIfAbsent(tag.trim(), k -> ConcurrentHashMap.newKeySet())
                            .add(clientId);
                }
            }
        }

        log.info("节点注册成功: {}, nodeType={}, userId={}, tags={}, capabilities={}",
                clientId, info.getNodeType(), info.getUserId(), info.getTags(), info.getCapabilities());

        // 异步为用户预初始化 Agent 工作空间
        if (info.getUserId() != null && !info.getUserId().isBlank()) {
            String userId = info.getUserId();
            CompletableFuture.runAsync(() -> {
                try {
                    userWorkspaceService.getOrCreateUserAgent("food-chat", userId);
                    log.info("用户 Agent 工作空间预初始化完成: userId={}", userId);
                } catch (Exception e) {
                    log.warn("用户 Agent 工作空间预初始化失败（非关键）: userId={}, {}", userId, e.getMessage());
                }
            });
        }

        // 持久化节点画像
        try {
            profileService.saveFromNodeInfo(info);
        } catch (Exception e) {
            log.warn("[Relay] 节点画像持久化失败: clientId={}", clientId, e);
        }

        sendMessage(session, Map.of(
                "type", "registered",
                "clientId", clientId,
                "message", "注册成功"));
    }

    /**
     * 处理命令执行结果
     */
    private void handleCommandResult(Map<String, Object> data) {
        String requestId = (String) data.get("requestId");
        if (requestId != null) {
            pendingRequests.remove(requestId);
            log.info("命令执行完成: requestId={}, status={}", requestId, data.get("status"));
            eventPublisher.publishEvent(new CommandResultEvent(requestId, data));
        }
    }

    /**
     * 处理命令执行错误
     */
    private void handleCommandError(Map<String, Object> data) {
        String requestId = (String) data.get("requestId");
        if (requestId != null) {
            pendingRequests.remove(requestId);
            log.error("命令执行失败: requestId={}, error={}", requestId, data.get("message"));
            eventPublisher.publishEvent(new CommandResultEvent(requestId, data));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String clientId = (String) session.getAttributes().get("clientId");
        if (clientId != null) {
            clients.remove(clientId);
            NodeInfo info = nodeRegistry.remove(clientId);

            // 清理 userId 映射
            if (info != null && info.getUserId() != null) {
                Set<String> userNodes = userNodeMap.get(info.getUserId());
                if (userNodes != null) {
                    userNodes.remove(clientId);
                    if (userNodes.isEmpty()) {
                        userNodeMap.remove(info.getUserId());
                    }
                }
            }

            // 清理 tag 映射
            if (info != null && info.getTags() != null) {
                for (String tag : info.getTags()) {
                    Set<String> tagNodes = tagNodeMap.get(tag);
                    if (tagNodes != null) {
                        tagNodes.remove(clientId);
                        if (tagNodes.isEmpty()) {
                            tagNodeMap.remove(tag);
                        }
                    }
                }
            }

            log.info("客户端已断开: {}, 当前在线 {}", clientId, clients.size());

            // 更新节点在线状态
            try {
                profileService.updateOnlineStatus(clientId, false);
            } catch (Exception e) {
                log.warn("[Relay] 更新在线状态失败: clientId={}", clientId, e);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String clientId = (String) session.getAttributes().get("clientId");
        log.error("WebSocket 传输错误, clientId: {}, error: {}", clientId, exception.getMessage());
        session.close();
    }

    /**
     * 定时心跳
     */
    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        if (clients.isEmpty()) {
            return;
        }

        Map<String, Object> heartbeat = Map.of("type", "ping");

        clients.forEach((clientId, session) -> {
            if (session.isOpen()) {
                try {
                    sendMessage(session, heartbeat);
                    NodeInfo info = nodeRegistry.get(clientId);
                    if (info != null) {
                        info.setLastHeartbeat(System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    log.warn("心跳发送失败: {}, error: {}", clientId, e.getMessage());
                }
            }
        });
    }

    /**
     * 清理超时客户端
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupTimeoutClients() {
        long now = System.currentTimeMillis();
        long timeout = 120000;

        nodeRegistry.entrySet().removeIf(entry -> {
            NodeInfo info = entry.getValue();
            if (now - info.getLastHeartbeat() > timeout) {
                String clientId = entry.getKey();
                WebSocketSession session = clients.get(clientId);
                if (session != null && session.isOpen()) {
                    try {
                        session.close();
                    } catch (IOException e) {
                        log.warn("关闭超时连接失败: {}", clientId);
                    }
                }

                // 清理 userId 映射
                if (info.getUserId() != null) {
                    Set<String> userNodes = userNodeMap.get(info.getUserId());
                    if (userNodes != null) {
                        userNodes.remove(clientId);
                        if (userNodes.isEmpty()) {
                            userNodeMap.remove(info.getUserId());
                        }
                    }
                }

                // 清理 tag 映射
                if (info.getTags() != null) {
                    for (String tag : info.getTags()) {
                        Set<String> tagNodes = tagNodeMap.get(tag);
                        if (tagNodes != null) {
                            tagNodes.remove(clientId);
                            if (tagNodes.isEmpty()) {
                                tagNodeMap.remove(tag);
                            }
                        }
                    }
                }

                clients.remove(clientId);
                log.info("已清理超时客户端: {}", clientId);
                return true;
            }
            return false;
        });
    }

    // ===== 查询接口 =====

    /**
     * 按 userId 获取在线 clientId 列表
     */
    public List<String> getClientIdsByUserId(String userId) {
        Set<String> nodeIds = userNodeMap.get(userId);
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        return nodeIds.stream()
                .filter(this::isClientOnline)
                .collect(Collectors.toList());
    }

    /**
     * 按 tag 获取在线 clientId 列表
     */
    public List<String> getClientIdsByTag(String tag) {
        Set<String> nodeIds = tagNodeMap.get(tag);
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        return nodeIds.stream()
                .filter(this::isClientOnline)
                .collect(Collectors.toList());
    }

    /**
     * 按多个 tag 取交集（必须同时拥有所有指定 tag）
     */
    public List<String> getNodesByTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        List<String> result = null;
        for (String tag : tags) {
            Set<String> nodeIds = tagNodeMap.get(tag);
            if (nodeIds == null || nodeIds.isEmpty()) {
                return List.of();
            }
            List<String> online = nodeIds.stream()
                    .filter(this::isClientOnline)
                    .collect(Collectors.toList());
            if (result == null) {
                result = new ArrayList<>(online);
            } else {
                result.retainAll(online);
            }
        }

        return result != null ? result : List.of();
    }

    /**
     * 获取节点信息
     */
    public NodeInfo getNodeInfo(String clientId) {
        return nodeRegistry.get(clientId);
    }

    // ===== 发送接口 =====

    /**
     * 向指定客户端发送消息
     */
    public void sendToClient(String clientId, Map<String, Object> message) {
        WebSocketSession session = clients.get(clientId);
        if (session != null && session.isOpen()) {
            Object requestId = message.get("requestId");
            if (requestId != null) {
                pendingRequests.put(requestId.toString(), clientId);
            }
            sendMessage(session, message);
        } else {
            log.warn("目标客户端不在线: {}", clientId);
        }
    }

    /**
     * 向全部在线客户端广播
     */
    public void broadcast(Map<String, Object> message) {
        clients.forEach((clientId, session) -> {
            if (session.isOpen()) {
                sendMessage(session, message);
            }
        });
    }

    /**
     * 向多个客户端发送消息
     */
    public Map<String, String> sendToClients(List<String> clientIds, Map<String, Object> message) {
        Map<String, String> results = new HashMap<>();
        for (String clientId : clientIds) {
            if (isClientOnline(clientId)) {
                String requestId = (message.get("requestId") != null)
                        ? message.get("requestId") + "-" + clientId
                        : UUID.randomUUID().toString();
                Map<String, Object> msg = new HashMap<>(message);
                msg.put("requestId", requestId);
                sendToClient(clientId, msg);
                results.put(clientId, requestId);
            } else {
                results.put(clientId, "OFFLINE");
            }
        }
        return results;
    }

    // ===== 状态接口 =====

    /**
     * 获取全部在线客户端
     */
    public Map<String, NodeInfo> getOnlineClients() {
        return new ConcurrentHashMap<>(nodeRegistry);
    }

    /**
     * 获取在线数量
     */
    public int getClientCount() {
        return clients.size();
    }

    /**
     * 判断客户端是否在线
     */
    public boolean isClientOnline(String clientId) {
        WebSocketSession session = clients.get(clientId);
        return session != null && session.isOpen();
    }

    // ===== 内部方法 =====

    /**
     * 通过 WebSocket 发送 JSON
     */
    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }

    /**
     * 从 URL 参数或 Session 属性中提取 clientId
     */
    private String getClientId(WebSocketSession session) {
        String query = session.getUri().getQuery();
        if (query != null && query.contains("clientId=")) {
            for (String param : query.split("&")) {
                if (param.startsWith("clientId=")) {
                    return param.substring(9);
                }
            }
        }
        return (String) session.getAttributes().get("clientId");
    }

    /**
     * 命令结果事件
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class CommandResultEvent {
        private final String requestId;
        private final Map<String, Object> result;
    }
}
