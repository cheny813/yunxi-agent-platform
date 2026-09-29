package io.yunxi.platform.ingress.a2a.dto;

import java.util.List;
import java.util.Map;

/**
 * A2A 协议注册请求。
 */
public record RegisterRequest(
        String agentName,
        String host,
        int port,
        String protocol,
        Map<String, String> metadata,
        List<String> capabilities) {
}
