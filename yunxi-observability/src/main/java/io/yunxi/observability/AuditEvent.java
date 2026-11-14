package io.yunxi.observability;

import java.time.Instant;
import java.util.Map;

/**
 * 可观测平面。由横切中间件链（如工具调用鉴权、画像演进）发出的结构化审计事件。不携带任何推理语义。
 */
public record AuditEvent(String type, String actor, Instant at, Map<String, Object> payload) {
}
