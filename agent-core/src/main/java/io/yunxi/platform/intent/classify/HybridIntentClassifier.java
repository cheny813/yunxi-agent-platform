package io.yunxi.platform.intent.classify;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.intent.config.IntentProperties;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.tracing.LlmMetrics;

/**
 * 混合意图分类器（编排 {rule, llm, hybrid} 三种通道）。
 *
 * <p>当 {@code yunxi.intent.classification.mode} 为 {@code hybrid} 或 {@code llm} 时
 * 以 {@code @Primary} 注册（mode=rule 时本类不装配，直连 {@link RuleIntentClassifier}）。
 * 组合规则分类器 + LLM 白名单分类器 + 结果缓存，编排如下：</p>
 *
 * <pre>
 * hybrid 模式：
 *   1. 规则分类（normalizedScore 语义）
 *   2. 规则高分（≥ rule-confidence-threshold）→ 直出（matchedBy=RULE）
 *   3. 缓存命中 → 直出（matchedBy=LLM-CACHE）
 *   4. 规则低置信但有场景命中（sceneName 非 GENERAL）→ 三级链兜底直出（matchedBy=RULE）
 *   5. LLM 白名单分类；命中 → 写缓存并直出（matchedBy=LLM）
 *   6. 规则结果标记 RULE-FALLBACK 保底（可能为 unknown）
 * llm 模式：跳过规则匹配直接走 LLM + 缓存；失败返回 unknown（matchedBy=NONE）
 * </pre>
 *
 * <p>{@code classification.llm.enabled=false} 时退化为纯规则通道（不调 LLM、不读缓存）。</p>
 */
@Component
@Primary
@Conditional(HybridIntentClassifier.OnHybridOrLlmMode.class)
public class HybridIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(HybridIntentClassifier.class);

    /** GENERAL 场景常量（与 RuleIntentClassifier 一致） */
    private static final String GENERAL = "GENERAL";

    private final IntentProperties properties;
    private final RuleIntentClassifier ruleClassifier;
    private final LlmIntentClassifier llmClassifier;
    private final LlmResultCache llmCache;
    private final boolean llmEnabled;

    public HybridIntentClassifier(IntentProperties properties, RuleIntentClassifier ruleClassifier,
            ModelFactory modelFactory, LlmMetrics llmMetrics, ObjectMapper objectMapper) {
        this.properties = properties;
        this.ruleClassifier = ruleClassifier;
        IntentProperties.Classification classification = properties.getClassification();
        this.llmEnabled = classification.getLlm().isEnabled();
        this.llmClassifier = new LlmIntentClassifier(modelFactory, llmMetrics, objectMapper,
                classification.getLlm());
        IntentProperties.Classification.Cache cache = classification.getLlm().getCache();
        this.llmCache = new LlmResultCache(Duration.ofDays(cache.getTtlDays()), cache.getMaxSize());
    }

    @Override
    public Intent classify(String query, List<Entity> entities, String sceneName, DomainRuntime runtime) {
        if (!llmEnabled) {
            return ruleClassifier.classify(query, entities, sceneName, runtime);
        }
        String mode = properties.getClassification().getMode();
        if ("llm".equals(mode)) {
            return classifyLlmOnly(query, entities, runtime);
        }
        return classifyHybrid(query, entities, sceneName, runtime);
    }

    /** llm 模式：跳过规则匹配，直接 LLM + 缓存 */
    private Intent classifyLlmOnly(String query, List<Entity> entities, DomainRuntime runtime) {
        if (runtime == null || runtime.tree() == null) {
            return Intent.unknown();
        }
        String key = LlmResultCache.key(runtime.name(), query, runtime.version());
        Intent cached = llmCache.get(key);
        if (cached != null) {
            return cached.withMatchedBy(Intent.MATCHED_LLM_CACHE);
        }
        Intent llm = llmClassifier.classify(query, entities, null, runtime);
        if (!llm.isUnknown()) {
            llmCache.put(key, llm);
        }
        return llm;
    }

    /** hybrid 模式编排（规则高分直出 → 缓存 → 三级链兜底 → LLM → 规则保底） */
    private Intent classifyHybrid(String query, List<Entity> entities, String sceneName, DomainRuntime runtime) {
        IntentProperties.Classification classification = properties.getClassification();

        // 1. 规则通道（hybrid 下输出归一化置信）
        Intent rule = ruleClassifier.classify(query, entities, sceneName, runtime);

        // 2. 规则高分直出
        if (!rule.isUnknown() && rule.confidence() >= classification.getRuleConfidenceThreshold()) {
            log.debug("[INTENT] 规则高分直出: intentId={} confidence={}", rule.intentId(), rule.confidence());
            return rule;
        }

        // 3. LLM 结果缓存命中
        String key = LlmResultCache.key(runtime == null ? "" : runtime.name(), query,
                runtime == null ? 0 : runtime.version());
        Intent cached = llmCache.get(key);
        if (cached != null) {
            log.debug("[INTENT] LLM 缓存命中: intentId={} key={}", cached.intentId(), key);
            return cached.withMatchedBy(Intent.MATCHED_LLM_CACHE);
        }

        // 4. 三级链兜底：规则低置信但场景确定性高（sceneName 非 GENERAL 命中）→ 直出
        if (!rule.isUnknown() && sceneName != null && !GENERAL.equals(sceneName)) {
            log.debug("[INTENT] 场景兜底直出: intentId={} sceneName={} confidence={}",
                    rule.intentId(), sceneName, rule.confidence());
            return rule;
        }

        // 5. LLM 白名单分类；命中写缓存
        Intent llm = llmClassifier.classify(query, entities, sceneName, runtime);
        if (!llm.isUnknown()) {
            llmCache.put(key, llm);
            log.debug("[INTENT] LLM 命中: intentId={} confidence={}", llm.intentId(), llm.confidence());
            return llm;
        }

        // 6. 规则结果 RULE-FALLBACK 保底（可能为 unknown）
        log.debug("[INTENT] LLM 未命中，规则结果降级 RULE-FALLBACK: intentId={} unknown={}",
                rule.intentId(), rule.isUnknown());
        return rule.withMatchedBy(Intent.MATCHED_RULE_FALLBACK);
    }

    @Override
    public String detectSceneName(String query) {
        return ruleClassifier.detectSceneName(query);
    }

    /**
     * LLM 热度建议词（供 {@code /actuator/intent/suggest-words} 观测端点）。
     *
     * <p>domain 可空 = 全域热度 TopN；按缓存 key 前缀 {@code domain|} 过滤。
     * mode=rule 时本类不装配，端点经 ObjectProvider 优雅降级为 enabled=false。</p>
     */
    public List<Intent> hotSuggestions(String domain, int topN) {
        return llmCache.hotEntries(domain, topN);
    }

    /** 装配条件：mode ∈ {hybrid, llm} 时注册；mode=rule 时不装配（直连规则分类器） */
    static class OnHybridOrLlmMode implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String mode = context.getEnvironment()
                    .getProperty("yunxi.intent.classification.mode", "rule");
            return "hybrid".equalsIgnoreCase(mode) || "llm".equalsIgnoreCase(mode);
        }
    }
}
