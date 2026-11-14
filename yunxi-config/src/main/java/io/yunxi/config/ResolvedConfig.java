package io.yunxi.config;

import java.util.List;
import java.util.Map;

/**
 * 配置平面。由配置中心产出的 {@link io.yunxi.definition.AgentSpec} 完全解析视图。运行时平面只消费此类型，从不读取原始片段。
 */
public record ResolvedConfig(
        String primaryModel,
        List<String> fallbackModels,
        Integer maxIters,
        Map<String, Object> state) {
}
