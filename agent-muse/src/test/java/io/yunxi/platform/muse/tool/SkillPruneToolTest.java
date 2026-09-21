package io.yunxi.platform.muse.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import io.yunxi.platform.muse.engine.SelfEvolutionEngine;
import io.yunxi.platform.muse.prune.DefaultSkillPruner;
import io.yunxi.platform.muse.prune.UsageProfile;
import io.yunxi.platform.muse.prune.UsageProfileAggregator;
import io.yunxi.platform.muse.config.EvolutionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SkillPruneTool：画像维度自动补充与剪枝决策联动测试。
 */
@DisplayName("MUSE 剪枝工具（画像自动补充）")
class SkillPruneToolTest {

    private SelfEvolutionEngine engine;
    private UsageProfileAggregator aggregator;
    private SkillPruneTool tool;
    private EvolutionConfig config;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        config = new EvolutionConfig();
        config.getPruner().setMinUsage(1);
        engine = mock(SelfEvolutionEngine.class);
        when(engine.getPruner()).thenReturn(new DefaultSkillPruner(config));
        aggregator = mock(UsageProfileAggregator.class);
        tool = new SkillPruneTool(engine, providerOf(aggregator));
    }

    @Test
    @DisplayName("画像聚合可用：低使用率技能因使用者广度被撤销归档")
    void profileRescindsArchive() {
        config.getPruner().setMinUsage(2);
        config.getPruner().getProfileAware().setGeneralUsageThreshold(3);
        // 聚合器返回 5 个不同使用者 → 应撤销归档
        when(aggregator.aggregate("general_skill"))
                .thenReturn(new UsageProfile(5, Map.of("职业", 5), 5));

        String result = tool.prune("[{\"name\":\"general_skill\",\"usage_count\":1,\"content\":null}]");

        assertThat(result).doesNotContain("ARCHIVE");
        verify(aggregator).aggregate("general_skill");
    }

    @Test
    @DisplayName("单一用户高频：标记为过拟合（需泛化）")
    void profileMarksOverfit() {
        config.getPruner().getProfileAware().setOverfitUsageThreshold(5);
        when(aggregator.aggregate("personal_skill"))
                .thenReturn(new UsageProfile(1, Map.of("职业", 1), 10));

        String result = tool.prune("[{\"name\":\"personal_skill\",\"usage_count\":10,\"content\":null}]");

        assertThat(result).contains("OVERFIT");
    }

    @Test
    @DisplayName("聚合不可用：保持原有低使用率归档判据")
    void nullProfileKeepsLegacyDecision() {
        config.getPruner().setMinUsage(1);
        when(aggregator.aggregate(any())).thenReturn(null);

        String result = tool.prune("[{\"name\":\"dead_skill\",\"usage_count\":0,\"content\":null}]");

        assertThat(result).contains("ARCHIVE");
    }

    @Test
    @DisplayName("聚合器组件缺失：不调用聚合，按原有判据执行")
    void missingAggregatorSkipsEnrichment() {
        SkillPruneTool noAgg = new SkillPruneTool(engine, providerOf(null));

        String result = noAgg.prune("[{\"name\":\"dead_skill\",\"usage_count\":0,\"content\":null}]");

        assertThat(result).contains("ARCHIVE");
    }

    @Test
    @DisplayName("聚合抛异常：被隔离，剪枝仍产出决策")
    void aggregatorFailureIsolated() {
        when(aggregator.aggregate(any())).thenThrow(new RuntimeException("redis down"));

        String result = tool.prune("[{\"name\":\"dead_skill\",\"usage_count\":0,\"content\":null}]");

        assertThat(result).contains("ARCHIVE");
    }

    @Test
    @DisplayName("入参非法 JSON：返回可读错误而非抛出")
    void invalidJsonReturnsMessage() {
        String result = tool.prune("not-a-json");

        assertThat(result).contains("解析 skills_json 失败");
    }
}
