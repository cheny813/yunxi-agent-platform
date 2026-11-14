package io.yunxi.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 可观测平面。单条 Agent 轨迹（一次用户轮次）。运行时推理循环只通过 TraceStore 产出它们；绝不读取或修改（存储从热路径中剥离）。
 */
public record TraceRecord(
        String traceId,
        String userId,
        String sessionId,
        Instant createdAt,
        List<Map<String, Object>> spans) {
}
