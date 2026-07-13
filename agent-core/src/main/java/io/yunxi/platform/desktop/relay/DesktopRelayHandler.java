package io.yunxi.platform.desktop.relay;

import com.fasterxml.jackson.databind.ObjectMapper;

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
    public DesktopRelayHandler(ApplicationEventPublisher eventPublisher, NodeProfileService profileService) {
        this.eventPublisher = eventPublisher;
        this.profileService = profileService;
    }

    /** WebSocket 连接建立时回调，分配 clientId 并发送欢迎消息。
     * @param session 新建的 WebSocket 会话
     */
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

    /** 接收并分发客户端上报的文本消息（register/pong/result/error）。
     * @param session 发送消息的会话
     * @param message 消息内容
     */
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
    /**
     * 处理桌面客户端注册请求，解析并构建节点信息、维护 userId/tag 索引。
     *
     * @param session WebSocket 会话
     * @param data    客户端上报的注册数据（userId/nodeType/tags/hostname/os 等）
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

        // 多租户工作空间隔离已由 GA 原生（HarnessAgent.workspaceFor）在运行时按
        // RuntimeContext(userId, sessionId) 自动处理，无需在此预初始化用户工作空间。

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
    /**
     * 处理客户端命令执行结果，发布命令结果事件供等待方消费。
     *
     * @param data 含 requestId 与结果的数据
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
    /**
     * 处理客户端命令执行错误，发布命令结果事件（含错误数据）。
     *
     * @param data 含 requestId 与错误信息的数据
     */
    private void handleCommandError(Map<String, Object> data) {
        String requestId = (String) data.get("requestId");
        if (requestId != null) {
            pendingRequests.remove(requestId);
            log.error("命令执行失败: requestId={}, error={}", requestId, data.get("message"));
            eventPublisher.publishEvent(new CommandResultEvent(requestId, data));
        }
    }

    /** WebSocket 连接关闭时回调，清理客户端及其 userId/tag 索引并更新在线状态。
     * @param session 关闭的会话
     * @param status  关闭状态
     */
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
    /**
     * 按 userId 获取其关联的在线 clientId 列表。
     *
     * @param userId 用户唯一标识
     * @return 在线客户端ID列表（无则空列表）
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
    /**
     * 按 tag 获取关联的在线 clientId 列表。
     *
     * @param tag 标签
     * @return 在线客户端ID列表（无则空列表）
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
    /**
     * 按多个 tag 取交集获取节点（必须同时拥有所有指定 tag 且在线）。
     *
     * @param tags 标签列表
     * @return 同时满足所有标签的在线客户端ID列表（无则空列表）
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
    /**
     * 获取指定客户端的节点信息。
     *
     * @param clientId 客户端ID
     * @return 节点信息；不存在时返回 null
     */
    public NodeInfo getNodeInfo(String clientId) {
        return nodeRegistry.get(clientId);
    }

    // ===== 发送接口 =====

    /**
     * 向指定客户端发送消息
     */
    /**
     * 向指定客户端发送消息（在线则发送并记录待处理请求）。
     *
     * @param clientId 客户端ID
     * @param message  待发送的消息 Map
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
    /**
     * 向全部在线客户端广播消息。
     *
     * @param message 待广播的消息 Map
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
    /**
     * 向多个客户端发送消息，为每个离线目标标记 OFFLINE。
     *
     * @param clientIds 目标客户端ID列表
     * @param message   待发送的消息 Map
     * @return 各客户端ID到请求ID（或 OFFLINE）的映射
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
    /**
     * 获取全部在线客户端的节点信息快照。
     *
     * @return clientId 到 NodeInfo 的映射
     */
    public Map<String, NodeInfo> getOnlineClients() {
        return new ConcurrentHashMap<>(nodeRegistry);
    }

    /**
     * 获取在线数量
     */
    /**
     * 获取当前在线客户端数量。
     *
     * @return 在线客户端数量
     */
    public int getClientCount() {
        return clients.size();
    }

    /**
     * 判断客户端是否在线
     */
    /**
     * 判断指定客户端是否在线（会话存在且处于打开状态）。
     *
     * @param clientId 客户端ID
     * @return 在线返回 true，否则返回 false
     */
    public boolean isClientOnline(String clientId) {
        WebSocketSession session = clients.get(clientId);
        return session != null && session.isOpen();
    }

    // ===== 内部方法 =====

    /**
     * 通过 WebSocket 发送 JSON
     */
    /**
     * 通过 WebSocket 向指定会话发送 JSON 消息。
     *
     * @param session WebSocket 会话
     * @param message 待发送的消息 Map
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
    /**
     * 从连接 URL 参数或会话属性中提取 clientId。
     *
     * @param session WebSocket 会话
     * @return 解析到的 clientId；不存在时返回 null
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
