package io.yunxi.intelligence;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 智能平面。关键词解析器（规则路径）。规则「意图标签 → 触发关键词列表」，可选
 * 「标签 → 目标 Agent」显式映射：命中且声明了目标时路由到该 Agent（修正旧实现
 * 命中后恒回落默认 Agent 的缺陷）；未声明目标的标签命中后仍以默认 Agent 兜底。
 * 未命中任何规则返回 {@code null}，由引擎决定回落路径。
 */
public class KeywordIntentResolver implements IntentResolver {

    private final Map<String, List<String>> rules;
    private final Map<String, String> targets;
    private final String defaultAgentId;
    private final IntentTextNormalizer normalizer;

    public KeywordIntentResolver(Map<String, List<String>> rules,
            Map<String, String> targets,
            String defaultAgentId,
            IntentTextNormalizer normalizer) {
        this.rules = rules != null ? rules : Map.of();
        this.targets = targets != null ? targets : Map.of();
        this.defaultAgentId = defaultAgentId;
        this.normalizer = normalizer;
    }

    @Override
    public IntentResolution resolve(IntentRequest request) {
        String text = normalizer.normalize(request.text()).toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> rule : rules.entrySet()) {
            if (rule.getValue() == null) {
                continue;
            }
            for (String keyword : rule.getValue()) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                if (text.contains(normalizer.normalize(keyword).toLowerCase(Locale.ROOT))) {
                    String target = targets.getOrDefault(rule.getKey(), defaultAgentId);
                    return new IntentResolution(rule.getKey(), 1.0d, target, Map.of(), null, IntentResolution.Source.KEYWORD);
                }
            }
        }
        return null;
    }
}
