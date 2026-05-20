package io.yunxi.platform.framework.memory;

import java.util.Map;
import java.util.UUID;

/**
 * 记忆记录表示
 */
public record MemoryRecord(
    String id,
    String content,
    Map<String, Object> metadata,
    long timestamp,
    String userId
) {
    public MemoryRecord(String content, Map<String, Object> metadata, String userId) {
        this(UUID.randomUUID().toString(), content, metadata, System.currentTimeMillis(), userId);
    }
}