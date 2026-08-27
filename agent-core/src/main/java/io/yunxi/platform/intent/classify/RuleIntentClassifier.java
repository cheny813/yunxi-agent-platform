package io.yunxi.platform.intent.classify;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.yunxi.platform.agent.profile.ConceptRegistry;
import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.intent.config.IntentProperties;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.memory.MemoryScene;
import io.yunxi.platform.memory.MemorySceneRegistry;
import io.yunxi.platform.intent.util.IntentKeywordMatcher;

/**
 * 规则版意图分类器。
 *
 * <p>三级链行为与旧 {@code SceneDetectionService} 严格等价（顺序：
 * MemorySceneRegistry 自定义场景 → ConceptRegistry 领域概念 → 内置关键词），
 * 保证记忆场景链路零回归。意图分类：场景关联优先，其次叶子优先关键词匹配。
 * 意图树数据自 {@link DomainRuntime#tree()} 快照读取（多域隔离）。</p>
 *
 * <p><b>置信语义</b>：mode=rule 时沿用基础 0.9 + 实体加分（+0.05/个，封顶 1.0）；
 * mode=hybrid 时输出归一化置信——{@code allKeywords} 全中 + 实体加分 = 1.0，
 * 部分关键词组命中按命中组占比降档（供混合编排区分高分直出与低置信走 LLM）。
 * 实体条件 {@code optional} 语义仅在 hybrid 模式启用（optional=false 缺失则节点不匹配，
 * 走 LLM 兜底；optional=true 缺失不扣分）。</p>
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

    /** hybrid 模式归一化置信开关（mode=hybrid 时启用 optional 语义与降档置信） */
    private final boolean normalizedScore;

    public RuleIntentClassifier(MemorySceneRegistry memorySceneRegistry,
            ObjectProvider<ConceptRegistry> conceptRegistryProvider, IntentProperties intentProperties) {
        this.memorySceneRegistry = memorySceneRegistry;
        this.conceptRegistryProvider = conceptRegistryProvider;
        this.normalizedScore = "hybrid".equals(intentProperties.getClassification().getMode());
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
    public Intent classify(String query, List<Entity> entities, String sceneName, DomainRuntime runtime) {
        if (runtime == null || runtime.tree() == null) {
            return Intent.unknown();
        }
        // 场景关联命中优先（null 视为 GENERAL：无场景名时跳过场景关联直连叶子匹配）
        String sceneKey = sceneName == null ? GENERAL : sceneName;
        IntentTree.Node node = runtime.tree().bySceneName().get(sceneKey);
        if (node != null) {
            return new Intent(node.id, node.label, sceneConfidence(node), Intent.MATCHED_RULE);
        }

        // 叶子优先关键词匹配
        for (IntentTree.Node n : runtime.tree().leafFirst()) {
            if (n.match == null) {
                continue;
            }
            if (matchNode(n.match, query, entities, normalizedScore)) {
                double confidence = normalizedScore
                        ? normalizedScore(n.match, query, entities)
                        : Math.min(1.0, 0.9 + 0.05 * IntentKeywordMatcher.bonusCount(n.match, entities));
                return new Intent(n.id, n.label, confidence, Intent.MATCHED_RULE);
            }
        }
        return Intent.unknown();
    }

    /**
     * hybrid 归一化置信：allKeywords 全中 + 实体加分 = 1.0（封顶）；
     * 部分关键词组命中按命中组占比降档 + 实体加分；无 allKeywords 时按
     * anyKeywords 弱信号给 0.55 基线（低于默认直出阈值 0.6，引导低置信走 LLM）。
     */
    private double normalizedScore(IntentTree.Match match, String query, List<Entity> entities) {
        String lower = IntentKeywordMatcher.lower(query);
        int bonus = IntentKeywordMatcher.bonusCount(match, entities);
        if (match.allKeywords != null && !match.allKeywords.isEmpty()) {
            int totalGroups = match.allKeywords.size();
            int hitGroups = IntentKeywordMatcher.hitAllGroupCount(match.allKeywords, lower);
            if (hitGroups >= totalGroups) {
                return 1.0;
            }
            return Math.min(1.0, (double) hitGroups / totalGroups + 0.05 * bonus);
        }
        return Math.min(1.0, 0.55 + 0.05 * bonus);
    }

    /** 场景关联直出置信度：节点级 confidence 覆盖优先，缺省 0.9 */
    private double sceneConfidence(IntentTree.Node node) {
        if (node.confidence != null) {
            return Math.max(0.0, Math.min(1.0, node.confidence));
        }
        return 0.9;
    }

    /**
     * 关键词判定：allKeywords 组内 AND（组内须全部命中）、组间 OR（任一组成即算）；
     * anyKeywords 任一命中。实体条件默认纯加分；hybrid 模式启用 optional 语义——
     * optional=false 的实体缺失则节点不匹配（走 LLM 兜底），optional=true 缺失不扣分。
     */
    private boolean matchNode(IntentTree.Match match, String query, List<Entity> entities,
            boolean hybridOptional) {
        String lower = IntentKeywordMatcher.lower(query);

        if (!IntentKeywordMatcher.allGroupHit(match.allKeywords, lower)) {
            return false;
        }
        if (!IntentKeywordMatcher.anyKeywordHit(match.anyKeywords, lower)) {
            return false;
        }

        if (hybridOptional && match.entities != null && !match.entities.isEmpty()) {
            for (IntentTree.EntityCond cond : match.entities) {
                if (cond.type == null || cond.optional) {
                    continue;
                }
                boolean present = entities != null
                        && entities.stream().anyMatch(e -> cond.type.equals(e.type()));
                if (!present) {
                    return false;
                }
            }
        }
        return true;
    }
}
