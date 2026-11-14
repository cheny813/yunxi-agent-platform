package io.yunxi.capability;

import java.util.List;

/**
 * 能力平面。路由决策的结果：主模型 id 加有序兜底列表。运行时平面通过配置的 ModelFactory 把这些 id 解析为存活的 {@code Model} 实例。
 */
public record RoutedModel(String primaryModelId, List<String> fallbackModelIds, boolean auto) {

    /** 向后兼容：非 auto 路由（构建期固定模型）。 */
    public RoutedModel(String primaryModelId, List<String> fallbackModelIds) {
        this(primaryModelId, fallbackModelIds, false);
    }
}
