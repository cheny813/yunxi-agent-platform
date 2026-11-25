package io.yunxi.intelligence.muse.prune;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yunxi.intelligence.muse.prune.SkillPruner.SkillMeta;
import io.yunxi.runtime.skilluse.SkillUseEvent;
import io.yunxi.runtime.skilluse.SkillUseEventType;
import io.yunxi.runtime.skilluse.SkillUsageStats;
import io.yunxi.runtime.skilluse.SkillUseEventStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 使用画像聚合器单测：事件表统计 → SkillMeta 服务端覆盖（useCount + 画像）。
 */
class UsageProfileAggregatorTest {

    private final Map<String, SkillUsageStats> stats = new HashMap<>();
    private final SkillUseEventStore store = new SkillUseEventStore() {
        @Override
        public void record(SkillUseEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, SkillUsageStats> statsFor(java.util.Collection<String> skills) {
            Map<String, SkillUsageStats> out = new HashMap<>();
            for (String s : skills) {
                if (stats.containsKey(s)) {
                    out.put(s, stats.get(s));
                }
            }
            return out;
        }
    };

    private final UsageProfileAggregator aggregator = new UsageProfileAggregator(store);

    @Test
    void serverStatsOverrideSelfReportedUsage() {
        stats.put("weather", new SkillUsageStats("weather", 2, 2, Instant.now()));
        SkillMeta meta = new SkillMeta("weather", 50, "content");

        aggregator.applyTo(List.of(meta));

        assertEquals(2, meta.getUsageCount());
        assertEquals(2, meta.getUsageProfile().getDistinctUsers());
    }

    @Test
    void unknownSkillFallsToZeroUsageWithoutProfile() {
        SkillMeta meta = new SkillMeta("ghost", 99, "content");

        aggregator.applyTo(List.of(meta));

        assertEquals(0, meta.getUsageCount());
        assertNull(meta.getUsageProfile());
    }

    @Test
    void emptyMetasIsNoop() {
        aggregator.applyTo(List.of());
        // 不抛异常即通过
    }
}
