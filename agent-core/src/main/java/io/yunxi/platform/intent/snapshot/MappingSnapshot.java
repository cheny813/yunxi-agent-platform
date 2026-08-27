package io.yunxi.platform.intent.snapshot;

import java.util.List;
import java.util.Map;

import io.yunxi.platform.intent.mapping.IntentMappingTable;

/**
 * 映射表快照。
 *
 * <p>现状为线性 List 查找，快照新增 byIntent 索引（性能提升，语义不变）。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record MappingSnapshot(Map<String, IntentMappingTable.Mapping> byIntent,
                              List<IntentMappingTable.Mapping> mappings) {

    /** 空快照（降级/未配置时使用） */
    public static MappingSnapshot empty() {
        return new MappingSnapshot(Map.of(), List.of());
    }
}
