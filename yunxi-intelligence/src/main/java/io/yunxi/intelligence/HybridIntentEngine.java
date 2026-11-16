package io.yunxi.intelligence;

import io.yunxi.observability.AuditEvent;
import io.yunxi.observability.AuditSink;
import io.yunxi.observability.MetricRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能平面。四段式意图引擎：L0 归一化与槽位抽取 → L1 候选收窄（租户过滤 + 唯一候选短路 +
 * 关键词收窄）→ L2 分类判定（本地决策模型，可空）→ L3 决策融合与三级降级（模型 → 关键词 → 默认）。
 *
 * <p>短路语义：空白文本、空候选集、单候选、关键词显式目标命中均零模型调用。
 * 任何实现级异常一律 fail-open（记录日志与计数后按兜底路径返回），入口永不中断。</p>
 */
public class HybridIntentEngine implements IntentEngine {

    private static final Logger LOG = LoggerFactory.getLogger(HybridIntentEngine.class);

    private final IntentTextNormalizer normalizer;
    private final SlotExtractor slotExtractor;
    private final AgentCandidateProvider candidateProvider;
    private final IntentResolver keywordResolver;
    private final IntentClassifier classifier;
    private final IntentProperties properties;
    private final MetricRegistry metrics;
    private final AuditSink auditSink;
    private final IntentDecider decider = new IntentDecider();

    /** 构造引擎。classifier 可为 null（keyword 策略或分类器未装配）。metrics / auditSink 可为 null。 */
    public HybridIntentEngine(IntentTextNormalizer normalizer,
            SlotExtractor slotExtractor,
            AgentCandidateProvider candidateProvider,
            IntentResolver keywordResolver,
            IntentClassifier classifier,
            IntentProperties properties,
            MetricRegistry metrics,
            AuditSink auditSink) {
        this.normalizer = normalizer;
        this.slotExtractor = slotExtractor;
        this.candidateProvider = candidateProvider;
        this.keywordResolver = keywordResolver;
        this.classifier = classifier;
        this.properties = properties;
        this.metrics = metrics;
        this.auditSink = auditSink;
    }

    @Override
    public IntentResolution resolve(IntentRequest request) {
        long start = System.nanoTime();
        try {
            return doResolve(request, start);
        } catch (Exception e) {
            LOG.warn("意图解析异常，按默认兜底处理：{}", e.toString());
            return finish(IntentResolution.fallback(properties.getDefaultAgentId()),
                    IntentDecider.Outcome.FALLBACK, request, 0, start);
        }
    }

    private IntentResolution doResolve(IntentRequest request, long start) {
        String text = normalizer.normalize(request.text());
        if (text.isBlank()) {
            return finish(IntentResolution.fallback(properties.getDefaultAgentId()),
                    IntentDecider.Outcome.FALLBACK, request, 0, start);
        }
        int limit = request.maxCandidates() > 0 ? request.maxCandidates() : properties.getMaxCandidates();
        List<AgentCandidate> candidates = loadCandidates(request, limit);
        if (candidates.isEmpty()) {
            count("yunxi.intent.candidates.empty");
            return finish(IntentResolution.fallback(properties.getDefaultAgentId()),
                    IntentDecider.Outcome.FALLBACK, request, 0, start);
        }
        Map<String, String> ruleEntities = properties.getRewrite().isEnabled()
                ? slotExtractor.extract(text)
                : Map.of();

        if (candidates.size() == 1) {
            AgentCandidate only = candidates.get(0);
            return finish(new IntentResolution(only.agentId(), 1.0d, only.agentId(), ruleEntities, null,
                    IntentResolution.Source.KEYWORD), IntentDecider.Outcome.KEYWORD, request, candidates.size(), start);
        }

        IntentResolution keyword = tryKeyword(request);
        // 规则声明的目标也必须落在本次候选闭集内：候选闭集由「目录 ∩ 租户允许集合」求得，
        // 规则与目录/分配不一致时按无规则命中处理，交由裁决回落，避免把请求导向闭集外的 Agent。
        if (keyword != null && keyword.hasTarget() && !containsAgent(candidates, keyword.targetAgentId())) {
            count("yunxi.intent.keyword.out_of_scope");
            keyword = null;
        }
        // 关键词显式目标命中 = 运维显式声明的路由，优先于模型，且零模型调用。
        if (keyword != null && keyword.hasTarget() && !keyword.targetAgentId().equals(properties.getDefaultAgentId())) {
            return finish(keyword, IntentDecider.Outcome.KEYWORD, request, candidates.size(), start);
        }

        IntentResolution proposal = (classifier != null) ? tryClassify(text, ruleEntities, candidates) : null;
        if (classifier != null && proposal == null) {
            count("yunxi.intent.resolve.llm_fail");
        }
        IntentDecision decision = decider.decide(
                proposal == null ? null : new IntentDecision(proposal, IntentDecider.Outcome.LLM_ACCEPTED),
                keyword == null ? null : new IntentDecision(keyword, IntentDecider.Outcome.KEYWORD),
                properties.getDefaultAgentId(),
                properties.getMinConfidence(),
                candidates);

        IntentResolution result = mergeEntities(decision.resolution(), ruleEntities);
        return finish(result, decision.outcome(), request, candidates.size(), start);
    }

    private static boolean containsAgent(List<AgentCandidate> candidates, String agentId) {
        return candidates.stream().anyMatch(candidate -> candidate.agentId().equals(agentId));
    }

    private List<AgentCandidate> loadCandidates(IntentRequest request, int limit) {
        try {
            return candidateProvider.candidates(request.tenantId(), limit);
        } catch (Exception e) {
            LOG.warn("候选读取失败，按空候选处理：{}", e.toString());
            return List.of();
        }
    }

    private IntentResolution tryKeyword(IntentRequest request) {
        try {
            return keywordResolver.resolve(request);
        } catch (Exception e) {
            LOG.debug("关键词解析异常：{}", e.toString());
            return null;
        }
    }

    private IntentResolution tryClassify(String text, Map<String, String> ruleEntities, List<AgentCandidate> candidates) {
        try {
            return classifier.classify(text, ruleEntities, candidates);
        } catch (Exception e) {
            LOG.warn("意图分类判定失败，降级关键词/默认：{}", e.toString());
            return null;
        }
    }

    /** 实体合并：规则槽位值优先，模型值补位；改写文本仅在改写启用时保留。 */
    private IntentResolution mergeEntities(IntentResolution resolution, Map<String, String> ruleEntities) {
        if (ruleEntities.isEmpty() && resolution.entities().isEmpty()) {
            return resolution;
        }
        Map<String, String> merged = new LinkedHashMap<>(resolution.entities());
        ruleEntities.forEach(merged::put);
        return new IntentResolution(resolution.label(), resolution.confidence(), resolution.targetAgentId(),
                merged, resolution.rewrittenQuery(), resolution.source());
    }

    private IntentResolution finish(IntentResolution resolution,
            IntentDecider.Outcome outcome,
            IntentRequest request,
            int candidateCount,
            long startNanos) {
        count("yunxi.intent.resolve." + counterSuffix(outcome));
        if (metrics != null) {
            metrics.recordGauge("yunxi.intent.latency.ms", (System.nanoTime() - startNanos) / 1_000_000.0d);
        }
        if (auditSink != null) {
            try {
                auditSink.emit(new AuditEvent(
                        "intent.resolve",
                        request.tenantId() == null ? request.sessionId() : request.tenantId(),
                        Instant.now(),
                        Map.of(
                                "label", resolution.label() == null ? "" : resolution.label(),
                                "agentId", resolution.targetAgentId() == null ? "" : resolution.targetAgentId(),
                                "confidence", resolution.confidence(),
                                "source", resolution.source() == null ? "" : resolution.source().name(),
                                "candidateCount", candidateCount,
                                "sessionId", request.sessionId() == null ? "" : request.sessionId())));
            } catch (Exception e) {
                LOG.debug("意图审计事件发送失败：{}", e.toString());
            }
        }
        return resolution;
    }

    private static String counterSuffix(IntentDecider.Outcome outcome) {
        return switch (outcome) {
            case LLM_ACCEPTED -> "llm";
            case KEYWORD -> "keyword";
            case REJECTED_NOT_CANDIDATE -> "rejected";
            case LOW_CONFIDENCE -> "low_conf";
            default -> "fallback";
        };
    }

    private void count(String name) {
        if (metrics != null) {
            metrics.incrementCounter(name);
        }
    }
}
