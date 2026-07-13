package io.yunxi.platform.memory;

import java.util.Map;
import java.util.UUID;

/**
 * 记忆记录表示
 * <p>承载单条记忆的标识、内容、元数据、时间戳与所属用户。</p>
 */
public record MemoryRecord(
    /** 记忆唯一标识 */
    String id,
    /** 记忆内容文本 */
    String content,
    /** 附加元数据（场景、来源等） */
    Map<String, Object> metadata,
    /** 记录创建时间戳（毫秒） */
    long timestamp,
    /** 所属用户标识 */
    String userId
) {
    /**
     * 便捷构造器：自动生成 id 与时间戳。
     *
     * @param content 记忆内容
     * @param metadata 附加元数据
     * @param userId 所属用户标识
     */
    public MemoryRecord(String content, Map<String, Object> metadata, String userId) {
        this(UUID.randomUUID().toString(), content, metadata, System.currentTimeMillis(), userId);
    }
}
