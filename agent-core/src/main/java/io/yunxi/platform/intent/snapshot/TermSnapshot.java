package io.yunxi.platform.intent.snapshot;

import java.util.List;
import java.util.Map;

/**
 * 术语表快照。
 *
 * <p>term → 标准语映射 + 按 key 长度倒序的键列表（长词优先匹配），
 * 字段对齐 {@code TerminologyProcessor} 的既有依赖。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record TermSnapshot(Map<String, String> terms, List<String> sortedKeys) {

    /** 空快照（降级/未配置时使用） */
    public static TermSnapshot empty() {
        return new TermSnapshot(Map.of(), List.of());
    }
}
