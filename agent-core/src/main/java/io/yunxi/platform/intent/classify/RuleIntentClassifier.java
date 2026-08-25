package io.yunxi.platform.intent.classify;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.yunxi.platform.agent.profile.ConceptRegistry;
import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.memory.MemoryScene;
import io.yunxi.platform.memory.MemorySceneRegistry;

/**
 * 规则版意图分类器（M1 核心）。
 *
 * <p>三级链行为与旧 {@code SceneDetectionService} 严格等价（顺序：
 * MemorySceneRegistry 自定义场景 → ConceptRegistry 领域概念 → 内置关键词），
 * 保证记忆场景链路零回归。意图分类：场景关联优先，其次叶子优先关键词匹配。</p>
 *
 * <p><b>注入注意</b>：ConceptRegistry 必须用 {@code ObjectProvider} 注入
 * （与旧 SceneDetectionService 一致，应对可能未注册的场景），
 * 否则 ConceptRegistry 未配置时应用启动失败。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class RuleIntentClassifier implements IntentClassifier {

    /** 内置关键词（与旧 SceneDetectionService 原样一致，勿改） */
    private static final List<String> BUILTIN_PERSONAL_KEYWORDS = List.of(
            "记忆", "我记得", "喜好", "我的喜好", "我的习惯", "我的偏好",
            "我的工作", "我的家庭", "我的", "我的经历", "小时候", "回忆",
            "职业", "工作", "经验", "擅长", "专业", "技能");

    private static final String GENERAL = "GENERAL";

    private final MemorySceneRegistry memorySceneRegistry;
    private final ObjectProvider<ConceptRegistry> conceptRegistryProvider;
    private final IntentTree intentTree;

    public RuleIntentClassifier(MemorySceneRegistry memorySceneRegistry,
            ObjectProvider<ConceptRegistry> conceptRegistryProvider,
            IntentTree intentTree) {
        this.memorySceneRegistry = memorySceneRegistry;
        this.conceptRegistryProvider = conceptRegistryProvider;
        this.intentTree = intentTree;
    }

    // ---------------------------------------------------------------- 三级链

    @Override
    public String detectSceneName(String query) {
        if (query == null || query.isEmpty()) {
            return GENERAL;
        }
        String scene = detectByRegistry(query);
        if (!GENERAL.equals(scene)) {
            return scene;
        }
        scene = detectByConcepts(query);
        if (!GENERAL.equals(scene)) {
            return scene;
        }
        return detectByBuiltinKeywords(query);
    }

    /** 1. MemorySceneRegistry 自定义场景关键词检测 */
    private String detectByRegistry(String text) {
        String lowerText = text.toLowerCase();
        for (var cs : memorySceneRegistry.getCustomScenes().values()) {
            if (cs.keywords() != null) {
                for (String keyword : cs.keywords()) {
                    if (lowerText.contains(keyword.toLowerCase())) {
                        return cs.name();
                    }
                }
            }
        }
        return GENERAL;
    }

    /** 2. ConceptRegistry 领域概念检测（取置信度最高域，分数 > 0 才命中） */
    private String detectByConcepts(String text) {
        ConceptRegistry registry = conceptRegistryProvider.getIfAvailable();
        if (registry == null) {
            return GENERAL;
        }
        Map<String, Double> domains = registry.detectDomains(text);
        if (domains.isEmpty()) {
            return GENERAL;
        }
        var best = domains.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (best != null && best.getValue() > 0) {
            return best.getKey();
        }
        return GENERAL;
    }

    /** 3. 内置关键词检测（兜底） */
    private String detectByBuiltinKeywords(String text) {
        String lowerText = text.toLowerCase();
        for (String keyword : BUILTIN_PERSONAL_KEYWORDS) {
            if (lowerText.contains(keyword)) {
                return MemoryScene.PERSONAL_ASSISTANT;
            }
        }
        return GENERAL;
    }

    // ---------------------------------------------------------------- 分类

    @Override
    public Intent classify(String query, List<Entity> entities, String sceneName) {
        // 场景关联命中优先（含 GENERAL→null 直接跳过）
        IntentTree.Node node = intentTree.findBySceneName(sceneName);
        if (node != null) {
            return new Intent(node.id, node.label, 0.9, "RULE_TREE");
        }

        // 叶子优先关键词匹配
        for (IntentTree.Node n : intentTree.nodesLeafFirst()) {
            if (n.match == null) {
                continue;
            }
            if (matchNode(n.match, query, entities)) {
                double confidence = Math.min(1.0, 0.9 + 0.05 * bonusCount(n.match, entities));
                return new Intent(n.id, n.label, confidence, "RULE_TREE");
            }
        }
        return Intent.unknown();
    }

    /** 关键词判定：allKeywords 组内 AND（组内须全部命中）、组间 OR（任一组成即算）；anyKeywords 任一命中；entities 纯加分 */
    private boolean matchNode(IntentTree.Match match, String query, List<Entity> entities) {
        String lower = query == null ? "" : query.toLowerCase();

        if (match.allKeywords != null && !match.allKeywords.isEmpty()) {
            boolean groupHit = false;
            for (List<String> group : match.allKeywords) {
                boolean hit = true;
                for (String w : group) {
                    if (!lower.contains(w.toLowerCase())) {
                        hit = false;
                        break;
                    }
                }
                if (hit) {
                    groupHit = true;
                    break;
                }
            }
            if (!groupHit) {
                return false;
            }
        }

        if (match.anyKeywords != null && !match.anyKeywords.isEmpty()) {
            boolean anyHit = false;
            for (String w : match.anyKeywords) {
                if (lower.contains(w.toLowerCase())) {
                    anyHit = true;
                    break;
                }
            }
            if (!anyHit) {
                return false;
            }
        }
        return true;
    }

    /** 加分计数：match.entities 中 type 与 NER 实体任一 type 相同则 +1 */
    private int bonusCount(IntentTree.Match match, List<Entity> entities) {
        if (match.entities == null || match.entities.isEmpty() || entities == null) {
            return 0;
        }
        int bonus = 0;
        for (IntentTree.EntityCond cond : match.entities) {
            if (cond.type == null) {
                continue;
            }
            for (Entity e : entities) {
                if (cond.type.equals(e.type())) {
                    bonus++;
                    break;
                }
            }
        }
        return bonus;
    }
}
