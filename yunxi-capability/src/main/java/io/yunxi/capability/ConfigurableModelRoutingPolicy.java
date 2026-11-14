package io.yunxi.capability;

import io.agentscope.core.agent.RuntimeContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于配置（catalog / defaults / routing）的模型路由策略。
 *
 * <p>两条路径：
 * <ul>
 *   <li>固定路由：agent 的 routing.primary 指向某个 catalog 逻辑名，构建期解析为框架模型 id。</li>
     *   <li>自动选型：routing.primary = {@value #AUTO}，每次调用由 {@link #resolveAuto(String, TaskSignals)}
     *       按任务信号从候选池动态选最便宜且满足能力的模型。</li>
 * </ul>
 */
public class ConfigurableModelRoutingPolicy implements ModelRoutingPolicy {

    /** 路由主模型设为该哨兵值表示启用自动选型（每次调用按任务信号动态选模型）。 */
    public static final String AUTO = "auto";

    /** 长文本分支阈值（估算 token）；超过则要求候选上下文窗口 >= 估算 token × 安全系数。 */
    static final int LONG_TEXT_THRESHOLD = 8000;
    /** 长文本安全系数：要求候选上下文窗口至少为估算 token 的该倍数。 */
    static final double LONG_TEXT_SAFETY = 1.5;

    private final ModelProperties properties;
    private final FineRankingScorer fineRankingScorer;

    /** 以默认规则式精排器构造策略。 */
    public ConfigurableModelRoutingPolicy(ModelProperties properties) {
        this(properties, new RuleBasedFineRankingScorer());
    }

    /** 注入模型配置与精排器，并在构造期做启动快速失败校验（defaults 类型一致 + Auto 候选池非空）。 */
    public ConfigurableModelRoutingPolicy(ModelProperties properties, FineRankingScorer fineRankingScorer) {
        this.properties = properties;
        this.fineRankingScorer = fineRankingScorer;
        validateDefaultsTypeConsistency();
        validateAutoCandidatePool();
    }

    /**
     * 启动期快速失败：defaults 指向的目录项必须存在且 type 一致。
     * 对话/推理主模型（defaults.llm）必须指向真正的对话模型，本地 decision/embedding 不得用作 llm 默认。
     */
    private void validateDefaultsTypeConsistency() {
        for (Map.Entry<String, String> entry : properties.getDefaults().entrySet()) {
            String category = entry.getKey();
            String logicalName = entry.getValue();
            ModelEntry modelEntry = properties.getCatalog().get(logicalName);
            if (modelEntry == null) {
                throw new IllegalStateException(
                        "模型默认分类[" + category + "] 指向未定义的目录项[" + logicalName
                                + "]，请在 yunxi.models.catalog 中声明。");
            }
            if (!category.equals(modelEntry.getType())) {
                throw new IllegalStateException(
                        "模型默认分类[" + category + "] 要求 type=" + category + "，但目录项[" + logicalName
                                + "] 的 type=" + modelEntry.getType() + "；对话/推理主模型（defaults.llm）"
                                + "必须指向真正的对话模型，本地决策/矢量模型不得用作 llm 默认。");
            }
        }
    }

    /** 启动期快速失败：Auto 选型至少需要一个 type=llm 的候选模型。 */
    private void validateAutoCandidatePool() {
        boolean hasLlm = properties.getCatalog().values().stream()
                .anyMatch(e -> "llm".equals(e.getType()));
        if (!hasLlm) {
            throw new IllegalStateException(
                    "Auto 模型选择需要至少一个 type=llm 的候选模型，请先在 yunxi.models.catalog 声明对话模型。");
        }
    }

    @Override
    public RoutedModel route(String agentId, String intent, RuntimeContext ctx) {
        AgentRoute agentRoute = properties.getRouting().get(agentId);
        if (agentRoute != null) {
            String primaryName = agentRoute.getPrimary();
            if (AUTO.equals(primaryName)) {
                // 自动选型：模型在每次调用时由 AutoModelProvider 按任务信号动态选择（见 resolveAuto）。
                // primaryModelId 留空，AgentConfigurer 读到 auto=true 后会注入 AutoModelProvider 而非固定 Model。
                return new RoutedModel(null, List.of(), true);
            }
            if (primaryName == null) {
                throw new IllegalStateException(
                        "agent[" + agentId + "] 路由 primary 为空，请在 application.yml 的 yunxi.models.routing 中声明。");
            }
            List<String> fallbackIds = new ArrayList<>();
            if (agentRoute.getFallbacks() != null) {
                for (String fallbackName : agentRoute.getFallbacks()) {
                    fallbackIds.add(toFrameworkId(fallbackName));
                }
            }
            return new RoutedModel(toFrameworkId(primaryName), fallbackIds);
        }
        // 未显式声明路由的 agent：系统默认自动选型（整个系统所有 agent 动态选模型）。
        // defaults.llm 仅保留给 resolveAuto 候选池耗尽时的最终兜底，不再作为主模型回退。
        return new RoutedModel(null, List.of(), true);
    }

    /**
     * 自动选型：按任务信号从候选池（catalog 中 type=llm 的全部项）选出「能力满足 + 成本最低」的模型，
     * 返回带候选序列的 {@link RoutedModel}（auto=false）。能力过滤后为空时放宽到全部 llm，保证总有候选；
     * 仍为空则回退到 defaults.llm。
     */
    public RoutedModel resolveAuto(String agentId, TaskSignals signals) {
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, ModelEntry> e : properties.getCatalog().entrySet()) {
            if ("llm".equals(e.getValue().getType()) && matches(signals, e.getValue())) {
                candidates.add(e.getKey());
            }
        }
        if (candidates.isEmpty()) {
            // 媒体 / 编码 / 长文本约束在池中无对应项时放宽（回退到全部 llm），避免无候选导致请求失败。
            for (Map.Entry<String, ModelEntry> e : properties.getCatalog().entrySet()) {
                if ("llm".equals(e.getValue().getType())) {
                    candidates.add(e.getKey());
                }
            }
        }
        if (candidates.isEmpty()) {
            return new RoutedModel(toFrameworkId(properties.getDefaults().get("llm")), List.of(), false);
        }
        candidates.sort(this::compareByCost);
        // 精排层（可选）：规则式之上用本地决策模型对候选二次排序；未启用或失败时沿用规则式结果。
        List<String> ranked = fineRankingScorer.rank(agentId, signals, candidates);
        if (ranked != null && !ranked.isEmpty()) {
            candidates = ranked;
        }
        String primary = toFrameworkId(candidates.get(0));
        List<String> fallbacks = candidates.subList(1, candidates.size()).stream()
                .map(this::toFrameworkId)
                .toList();
        return new RoutedModel(primary, fallbacks, false);
    }

    /** 候选池 llm 项的最大上下文窗口（用于 AutoModelProvider 的 getContextWindowSize 兜底），无则 0。 */
    public int maxCandidateContextWindow() {
        return properties.getCatalog().values().stream()
                .filter(e -> "llm".equals(e.getType()) && e.getContextWindow() != null)
                .mapToInt(ModelEntry::getContextWindow)
                .max()
                .orElse(0);
    }

    /** 候选是否满足任务信号的能力约束（媒体 / 工具密集 / 长文本）。 */
    private boolean matches(TaskSignals signals, ModelEntry entry) {
        List<String> strengths = entry.getStrengths() != null ? entry.getStrengths() : List.of();
        if (signals.mediaType() == TaskSignals.MediaType.VIDEO && !strengths.contains("video")) {
            return false;
        }
        if ((signals.mediaType() == TaskSignals.MediaType.IMAGE
                || signals.mediaType() == TaskSignals.MediaType.IMAGE_TEXT)
                && !strengths.contains("vision")) {
            return false;
        }
        if (signals.mediaType() == TaskSignals.MediaType.AUDIO && !strengths.contains("audio")) {
            return false;
        }
        if (signals.toolHeavy() && !strengths.contains("coding")) {
            return false;
        }
        if (signals.estimatedTokens() > LONG_TEXT_THRESHOLD) {
            int window = entry.getContextWindow() != null ? entry.getContextWindow() : 0;
            if (window > 0 && window < signals.estimatedTokens() * LONG_TEXT_SAFETY) {
                return false;
            }
        }
        return true;
    }

    /** 成本档升序；同档按上下文窗口降序（窗口越大越能兜底长文本）。 */
    private int compareByCost(String a, String b) {
        ModelEntry ea = properties.getCatalog().get(a);
        ModelEntry eb = properties.getCatalog().get(b);
        int r = Integer.compare(costRank(ea), costRank(eb));
        if (r != 0) {
            return r;
        }
        int wa = ea.getContextWindow() != null ? ea.getContextWindow() : 0;
        int wb = eb.getContextWindow() != null ? eb.getContextWindow() : 0;
        return Integer.compare(wb, wa);
    }

    private int costRank(ModelEntry e) {
        String t = e.getCostTier() != null ? e.getCostTier() : "standard";
        return switch (t) {
            case "cheap" -> 0;
            case "standard" -> 1;
            case "premium" -> 2;
            default -> 1;
        };
    }

    /** 把目录中的逻辑模型名解析为框架模型 id（provider:model）。 */
    public String toFrameworkId(String logicalName) {
        ModelEntry entry = properties.getCatalog().get(logicalName);
        if (entry == null || entry.getProvider() == null || entry.getModel() == null) {
            throw new IllegalStateException(
                    "模型目录未定义逻辑模型[" + logicalName + "]，请在 yunxi.models.catalog 中声明其 provider 与 model。");
        }
        return entry.getProvider() + ":" + entry.getModel();
    }
}
