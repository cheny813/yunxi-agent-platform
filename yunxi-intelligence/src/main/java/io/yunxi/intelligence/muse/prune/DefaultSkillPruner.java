package io.yunxi.intelligence.muse.prune;

import io.yunxi.intelligence.muse.config.EvolutionConfig;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认剪枝器：先按使用率标记低质技能归档，再按余弦相似度合并重复技能。
 *
 * <p>第三遍为可选的画像驱动判据：把「使用次数」升级为「使用者广度」——使用次数高可能是
 * 少数用户反复使用，使用次数低可能是刚上线。该判据只读用户画像，不反向修改画像；
 * 画像数据缺失或开关关闭时，行为与只有前两遍时完全一致。</p>
 *
 * <p>顺序设计：先按使用率归档，再用画像判据「翻案」——若某技能虽次数低但使用者广泛
 * （多用户都用到），说明它有通用价值，撤销归档；若次数高但只有单一用户在用，
 * 标记为过拟合（需泛化）。这样画像维度只做「修正」而不新增删除，避免误杀小众领域的核心技能。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultSkillPruner implements SkillPruner {

    private final EvolutionConfig config;
    private final SimpleSkillMerger merger = new SimpleSkillMerger();

    public DefaultSkillPruner(EvolutionConfig config) {
        this.config = config;
    }

    @Override
    public List<PruneDecision> review(List<SkillMeta> skills) {
        List<PruneDecision> decisions = new ArrayList<>();
        double simThreshold = config.getPruner().getSimilarityThreshold();
        int minUsage = config.getPruner().getMinUsage();

        // 第一遍：低使用率归档
        for (SkillMeta s : skills) {
            if (s.getUsageCount() < minUsage) {
                decisions.add(new PruneDecision(s.getName(), Action.ARCHIVE,
                        "使用次数 " + s.getUsageCount() + " < " + minUsage));
            }
        }

        // 第二遍：画像驱动判据（可选）。不新增删除动作——只对第一遍的结果「翻案」，
        // 以及对高频窄面技能打标记，降低误杀风险。
        applyProfileAwareRules(skills, decisions);

        // 第三遍：相似度合并（O(n^2)，技能库规模可控）
        for (int i = 0; i < skills.size(); i++) {
            for (int j = i + 1; j < skills.size(); j++) {
                SkillMeta a = skills.get(i);
                SkillMeta b = skills.get(j);
                if (a.getContent() == null || b.getContent() == null) continue;
                double sim = merger.similarity(a.getContent(), b.getContent());
                if (sim >= simThreshold) {
                    // 保留使用率更高的，合并另一个
                    SkillMeta keep = a.getUsageCount() >= b.getUsageCount() ? a : b;
                    SkillMeta drop = keep == a ? b : a;
                    decisions.add(new PruneDecision(drop.getName(), Action.MERGE,
                            String.format("与 %s 相似度 %.2f >= %.2f",
                                    keep.getName(), sim, simThreshold),
                            keep.getName()));
                }
            }
        }
        return decisions;
    }

    /**
     * 画像驱动判据：以使用者广度修正「仅看使用次数」的偏差。
     *
     * <p>两类处置：
     * <ul>
     *   <li>撤销归档：某技能被第一遍判为低使用率，但使用者数达到广度阈值 →
     *       多用户使用证明有通用价值，移除其归档决定。</li>
     *   <li>标记过拟合：去重使用者数为 1 且调用次数达到高频阈值 → 该技能服务于单一用户，
     *       疑为个性化过拟合，标记为需泛化（不删除）。</li>
     * </ul>
     *
     * <p>无画像数据（{@code usageProfile == null}）的技能一律跳过，保持原有判定。</p>
     */
    private void applyProfileAwareRules(List<SkillMeta> skills, List<PruneDecision> decisions) {
        EvolutionConfig.ProfileAware cfg = config.getPruner().getProfileAware();
        if (!cfg.isEnabled()) {
            return;
        }
        for (SkillMeta s : skills) {
            UsageProfile up = s.getUsageProfile();
            if (up == null) {
                continue;
            }
            // 撤销「因次数低而归档」：多用户使用说明有通用价值
            if (up.getDistinctUsers() >= cfg.getGeneralUsageThreshold()) {
                boolean removed = decisions.removeIf(d -> d.getName().equals(s.getName())
                        && d.getAction() == Action.ARCHIVE);
                if (removed) {
                    log.debug("[muse] 画像判据撤销归档: skill={}, distinctUsers={}",
                            s.getName(), up.getDistinctUsers());
                }
                continue;
            }
            // 标记过拟合：单一用户高频使用
            if (up.getDistinctUsers() == 1 && s.getUsageCount() >= cfg.getOverfitUsageThreshold()) {
                decisions.add(new PruneDecision(s.getName(), Action.OVERFIT,
                        String.format("仅 %d 个使用者但调用 %d 次(>= %d)，疑过拟合需泛化",
                                up.getDistinctUsers(), s.getUsageCount(),
                                cfg.getOverfitUsageThreshold())));
            }
        }
    }
}
