package io.yunxi.intelligence.muse.prune;

import io.yunxi.intelligence.muse.prune.SkillPruner.SkillMeta;
import io.yunxi.runtime.skilluse.SkillUsageStats;
import io.yunxi.runtime.skilluse.SkillUseEventStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.yunxi.runtime.config.MuseUsageEnabledCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 使用画像聚合器：从技能使用事件表（单一真相源）聚合出「使用次数 + 使用者广度」，
 * 以服务端数据覆盖剪枝元信息里的模型自报 usage——模型不再能伪造使用数据。
 *
 * <p>事件记录语义为「调用意向」（onActing 进入即记），不区分调用成败，
 * 故 successCalls/分类分布不填充（画像判据只消费 distinctUsers）。</p>
 */
@Component
@Conditional(MuseUsageEnabledCondition.class)
@RequiredArgsConstructor
public class UsageProfileAggregator {

    private final SkillUseEventStore store;

    /**
     * 就地对每个技能元信息做服务端覆盖。
     *
     * <p>有事件记录的技能：usageCount = 事件计数、usageProfile = 聚合画像（distinctUsers）；
     * 无事件记录的技能：usageCount = 0、usageProfile 保持 null（低使用率判据可命中）。</p>
     */
    public void applyTo(List<SkillMeta> metas) {
        if (metas == null || metas.isEmpty()) {
            return;
        }
        List<String> names = metas.stream().map(SkillMeta::getName).toList();
        Map<String, SkillUsageStats> stats = store.statsFor(names);
        for (SkillMeta meta : metas) {
            SkillUsageStats s = stats.get(meta.getName());
            if (s == null) {
                meta.setUsageCount(0);
                meta.setUsageProfile(null);
                continue;
            }
            meta.setUsageCount((int) Math.min(s.useCount(), Integer.MAX_VALUE));
            UsageProfile profile = new UsageProfile();
            profile.setDistinctUsers(s.distinctUsers());
            meta.setUsageProfile(profile);
        }
    }
}
