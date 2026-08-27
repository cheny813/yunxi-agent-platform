package io.yunxi.platform.intent.snapshot;

import java.util.List;
import java.util.Map;

import io.yunxi.platform.intent.ner.EntityDictionaryLoader;

/**
 * 实体词典快照。
 *
 * <p>不可变快照，三字段对齐 {@code RuleBasedNerStage} 的全部依赖：
 * {@code entries} 按 value 长度降序（长词优先防子串误匹配，语义与现状一致）；
 * {@code typePriorities} 为实体类型 → 优先级映射（未注册类型按 0 处理）。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record EntityDictSnapshot(List<EntityDictionaryLoader.DictEntry> entries,
                                 List<EntityDictionaryLoader.CompiledRegex> regexes,
                                 Map<String, Integer> typePriorities) {

    /** 空快照（降级/未配置时使用） */
    public static EntityDictSnapshot empty() {
        return new EntityDictSnapshot(List.of(), List.of(), Map.of());
    }
}
