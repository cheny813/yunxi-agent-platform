package io.yunxi.intelligence.muse.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.yunxi.intelligence.muse.engine.SelfEvolutionEngine;
import io.yunxi.intelligence.muse.prune.SkillPruner;
import io.yunxi.intelligence.muse.prune.SkillPruner.SkillMeta;
import io.yunxi.intelligence.muse.prune.UsageProfileAggregator;
import io.yunxi.runtime.skilluse.SkillUseEvent;
import io.yunxi.runtime.skilluse.SkillUsageStats;
import io.yunxi.runtime.skilluse.SkillUseEventStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 剪枝元工具单测：启用态服务端覆盖自报 usage 并注入画像；聚合器缺席时保持旧行为。
 */
class SkillPruneToolTest {

    private final SkillPruner pruner = mock(SkillPruner.class);
    private final SelfEvolutionEngine engine = mock(SelfEvolutionEngine.class);

    /** 内存桩存储：预置聚合结果。 */
    private static SkillUseEventStore store(Map<String, SkillUsageStats> stats) {
        return new SkillUseEventStore() {
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
    }

    private SkillPruneTool tool(UsageProfileAggregator aggregator) {
        @SuppressWarnings("unchecked")
        ObjectProvider<UsageProfileAggregator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(aggregator);
        when(engine.getPruner()).thenReturn(pruner);
        when(pruner.review(anyList())).thenReturn(List.of());
        return new SkillPruneTool(engine, provider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledPathOverridesUsageAndInjectsProfile() {
        UsageProfileAggregator aggregator = new UsageProfileAggregator(store(Map.of(
                "weather", new SkillUsageStats("weather", 2, 2, Instant.now()))));

        tool(aggregator).prune("[{\"name\":\"weather\",\"usage_count\":50,\"content\":\"a\"}]");

        ArgumentCaptor<List<SkillMeta>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(pruner).review(captor.capture());
        SkillMeta meta = captor.getValue().get(0);
        assertEquals(2, meta.getUsageCount());
        assertEquals(2, meta.getUsageProfile().getDistinctUsers());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownSkillFallsToZeroUsage() {
        UsageProfileAggregator aggregator = new UsageProfileAggregator(store(Map.of()));

        tool(aggregator).prune("[{\"name\":\"ghost\",\"usage_count\":99,\"content\":\"a\"}]");

        ArgumentCaptor<List<SkillMeta>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(pruner).review(captor.capture());
        SkillMeta meta = captor.getValue().get(0);
        assertEquals(0, meta.getUsageCount());
        assertNull(meta.getUsageProfile());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatorAbsentKeepsLegacyBehavior() {
        tool(null).prune("[{\"name\":\"weather\",\"usage_count\":50,\"content\":\"a\"}]");

        ArgumentCaptor<List<SkillMeta>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(pruner).review(captor.capture());
        assertEquals(50, captor.getValue().get(0).getUsageCount());
    }
}
