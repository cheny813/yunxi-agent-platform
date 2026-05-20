package io.yunxi.platform.gateway.controller;

import io.yunxi.platform.gateway.GatewayProperties;
import io.yunxi.platform.gateway.channel.ChannelHealth;
import io.yunxi.platform.gateway.channel.MessageChannel;
import io.yunxi.platform.gateway.core.GatewayDispatcher;
import io.yunxi.platform.gateway.core.GatewaySessionManager;
import io.yunxi.platform.gateway.model.GatewaySession;
import io.yunxi.platform.gateway.model.PlatformType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 网关管理 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    private final GatewayDispatcher dispatcher;
    private final GatewaySessionManager sessionManager;
    private final GatewayProperties properties;

    public GatewayController(GatewayDispatcher dispatcher,
                              GatewaySessionManager sessionManager,
                              GatewayProperties properties) {
        this.dispatcher = dispatcher;
        this.sessionManager = sessionManager;
        this.properties = properties;
    }

    /**
     * 获取网关状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("coreUrl", properties.getCoreUrl());
        status.put("defaultAgent", properties.getDefaultAgent());
        status.put("activeSessions", sessionManager.getActiveSessionCount());
        status.put("platforms", getPlatformStatuses());
        return ResponseEntity.ok(status);
    }

    /**
     * 获取各平台连接状态
     */
    @GetMapping("/channels")
    public ResponseEntity<List<Map<String, Object>>> getChannels() {
        return ResponseEntity.ok(getPlatformStatuses());
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> getSessions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", sessionManager.getActiveSessionCount());
        return ResponseEntity.ok(result);
    }

    /**
     * 启动指定平台
     */
    @PostMapping("/channels/{platform}/start")
    public ResponseEntity<Map<String, Object>> startChannel(@PathVariable String platform) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            PlatformType type = PlatformType.fromCode(platform);
            MessageChannel channel = dispatcher.getChannels().get(type.getCode());
            if (channel != null && !channel.isConnected()) {
                channel.connect(dispatcher::onMessage);
                result.put("success", true);
                result.put("message", "平台 " + platform + " 已启动");
            } else if (channel != null) {
                result.put("success", true);
                result.put("message", "平台 " + platform + " 已在运行");
            } else {
                result.put("success", false);
                result.put("message", "未找到平台适配器: " + platform);
            }
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", "未知平台: " + platform);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 停止指定平台
     */
    @PostMapping("/channels/{platform}/stop")
    public ResponseEntity<Map<String, Object>> stopChannel(@PathVariable String platform) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            PlatformType type = PlatformType.fromCode(platform);
            MessageChannel channel = dispatcher.getChannels().get(type.getCode());
            if (channel != null) {
                channel.disconnect();
                result.put("success", true);
                result.put("message", "平台 " + platform + " 已停止");
            } else {
                result.put("success", false);
                result.put("message", "未找到平台适配器: " + platform);
            }
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", "未知平台: " + platform);
        }
        return ResponseEntity.ok(result);
    }

    private List<Map<String, Object>> getPlatformStatuses() {
        List<Map<String, Object>> platforms = new ArrayList<>();
        for (Map.Entry<String, MessageChannel> entry : dispatcher.getChannels().entrySet()) {
            ChannelHealth health = entry.getValue().health();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("platform", entry.getKey());
            p.put("connected", health.isConnected());
            p.put("status", health.getStatus());
            if (health.getError() != null) {
                p.put("error", health.getError());
            }
            platforms.add(p);
        }
        return platforms;
    }
}
