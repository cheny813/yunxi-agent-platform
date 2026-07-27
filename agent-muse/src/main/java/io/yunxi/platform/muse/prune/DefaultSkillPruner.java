package io.yunxi.platform.muse.prune;

import io.yunxi.platform.muse.config.EvolutionConfig;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认剪枝器：先按使用率标记低质技能归档，再按余弦相似度合并重复技能。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
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

        // 第二遍：相似度合并（O(n^2)，技能库规模可控）
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
}
