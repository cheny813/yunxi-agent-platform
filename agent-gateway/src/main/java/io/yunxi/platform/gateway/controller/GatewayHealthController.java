package io.yunxi.platform.gateway.controller;

import io.yunxi.platform.gateway.GatewayProperties;
import io.yunxi.platform.gateway.channel.MessageChannel;
import io.yunxi.platform.gateway.core.GatewayDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 网关健康检查
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/health")
public class GatewayHealthController implements HealthIndicator {

    private final GatewayDispatcher dispatcher;
    private final GatewayProperties properties;

    public GatewayHealthController(GatewayDispatcher dispatcher, GatewayProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("coreUrl", properties.getCoreUrl());

        Map<String, Object> channels = new LinkedHashMap<>();
        boolean allHealthy = true;
        for (Map.Entry<String, MessageChannel> entry : dispatcher.getChannels().entrySet()) {
            var health = entry.getValue().health();
            channels.put(entry.getKey(), Map.of(
                    "connected", health.isConnected(),
                    "status", health.getStatus()
            ));
            if (!health.isConnected()) {
                allHealthy = false;
            }
        }
        result.put("channels", channels);
        result.put("status", allHealthy ? "UP" : "DEGRADED");
        return ResponseEntity.ok(result);
    }

    @Override
    public Health health() {
        boolean allHealthy = dispatcher.getChannels().values().stream()
                .allMatch(channel -> channel.health().isConnected());

        Health.Builder builder = allHealthy ? Health.up() : Health.up().withDetail("status", "DEGRADED");
        return builder
                .withDetail("coreUrl", properties.getCoreUrl())
                .withDetail("channelCount", dispatcher.getChannels().size())
                .build();
    }
}
