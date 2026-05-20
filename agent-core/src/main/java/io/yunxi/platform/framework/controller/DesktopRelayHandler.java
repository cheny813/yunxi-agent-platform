package io.yunxi.platform.framework.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.framework.profile.NodeProfileService;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 节点 WebSocket 中继控制器
 *
 * <p>
 * 桌面客户端和服务端节点连接到中继服务器，AI 通过后端服务转发命令到节点。
 * </p>
 *
 * <p>
 * 支持按 userId、tag 查询节点，支持批量命令下发。
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

    /** 客户端会话管理: clientId -> session */
    private final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();

    /** 注册的节点信息 */
    private final Map<String, NodeInfo> nodeRegistry = new ConcurrentHashMap<>();

    /** userId -> clientId 映射（一个用户可能有多个节点） */
    private final Map<String, Set<String>> userNodeMap = new ConcurrentHashMap<>();

    /** tag -> Set<clientId> 映射 */
    private final Map<String, Set<String>> tagNodeMap = new ConcurrentHashMap<>();

    /** 待响应的请求: requestId -> clientId */
    private final ConcurrentMap<String, String> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 构造桌面中继处理器
     *
     * @param eventPublisher  Spring 事件发布器
     * @param profileService 节点画像服务
     */
    public DesktopRelayHandler(ApplicationEventPublisher eventPublisher, NodeProfileService profileService) {
        this.eventPublisher = eventPublisher;
        this.profileService = profileService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String clientId = getClientId(session);
        if (clientId == null) {
            clientId = "node-" + System.currentTimeMillis();
        }

        clients.put(clientId, session);
        session.getAttributes().put("clientId", clientId);

        log.info("节点已连接: {}, 当前连接数: {}", clientId, clients.size());

        sendMessage(session, Map.of(
                "type", "welcome",
                "clientId", clientId,
                "message", "已连接到中继服务器"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到节点消息: {}", payload);

        try {
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            String type = (String) data.get("type");

            switch (type) {
                case "register":
                    handleRegister(session, data);
                    break;
                case "pong":
                    log.debug("收到节点心跳响应: {}", session.getAttributes().get("clientId"));
                    break;
                case "result":
                    handleCommandResult(data);
                    break;
                case "error":
                    handleCommandError(data);
                    break;
                default:
                    log.warn("未知的消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("解析节点消息失败: {}", e.getMessage());
        }
    }

    /**
     * 处理节点注册
     *
     * <p>
     * 解析增强字段（userId/nodeType/tags/hostname/os/localIp），构建映射索引。
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

        // capabilities 兼容 String 和 List 两种格式
        Object capabilities = data.get("capabilities");
        if (capabilities instanceof List) {
            info.setCapabilities(String.join(",", (List<String>) capabilities));
        } else if (capabilities instanceof String) {
            info.setCapabilities((String) capabilities);
        }

        // tags 兼容 String 和 List 两种格式
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

        // 构建 userId -> clientId 映射
        if (info.getUserId() != null && !info.getUserId().isBlank()) {
            userNodeMap.computeIfAbsent(info.getUserId(), k -> ConcurrentHashMap.newKeySet())
                    .add(clientId);
        }

        // 构建 tag -> clientId 映射
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

        // 保存画像到数据库
        try {
            profileService.saveFromNodeInfo(info);
        } catch (Exception e) {
            log.warn("[Relay] 保存画像失败: clientId={}", clientId, e);
        }

        sendMessage(session, Map.of(
                "type", "registered",
                "clientId", clientId,
                "message", "注册成功"));
    }

    private void handleCommandResult(Map<String, Object> data) {
        String requestId = (String) data.get("requestId");
        if (requestId != null) {
            pendingRequests.remove(requestId);
            log.info("命令执行完成: requestId={}, status={}", requestId, data.get("status"));
            eventPublisher.publishEvent(new CommandResultEvent(requestId, data));
        }
    }

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

            log.info("节点已断开: {}, 当前连接数: {}", clientId, clients.size());

            // 更新画像在线状态
            try {
                profileService.updateOnlineStatus(clientId, false);
            } catch (Exception e) {
                log.warn("[Relay] 更新画像在线状态失败: clientId={}", clientId, e);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String clientId = (String) session.getAttributes().get("clientId");
        log.error("WebSocket传输错误, clientId: {}, error: {}", clientId, exception.getMessage());
        session.close();
    }

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
                    log.warn("发送心跳失败: {}, error: {}", clientId, e.getMessage());
                }
            }
        });
    }

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
                        log.warn("关闭超时节点失败: {}", clientId);
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
                log.info("清理超时节点: {}", clientId);
                return true;
            }
            return false;
        });
    }

    // ===== 查询方法 =====

    /**
     * 按 userId 查询关联的节点 clientId 列表
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
     * 按 tag 查询关联的节点 clientId 列表
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
     * 按多个 tag 查询节点（交集：必须同时拥有所有指定 tag）
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

    // ===== 发送方法 =====

    public void sendToClient(String clientId, Map<String, Object> message) {
        WebSocketSession session = clients.get(clientId);
        if (session != null && session.isOpen()) {
            Object requestId = message.get("requestId");
            if (requestId != null) {
                pendingRequests.put(requestId.toString(), clientId);
            }
            sendMessage(session, message);
        } else {
            log.warn("节点不存在或已断开: {}", clientId);
        }
    }

    public void broadcast(Map<String, Object> message) {
        clients.forEach((clientId, session) -> {
            if (session.isOpen()) {
                sendMessage(session, message);
            }
        });
    }

    /**
     * 向多个节点发送命令
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

    // ===== 状态查询 =====

    public Map<String, NodeInfo> getOnlineClients() {
        return new ConcurrentHashMap<>(nodeRegistry);
    }

    public int getClientCount() {
        return clients.size();
    }

    public boolean isClientOnline(String clientId) {
        WebSocketSession session = clients.get(clientId);
        return session != null && session.isOpen();
    }

    // ===== 内部方法 =====

    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }

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
