package io.yunxi.platform.muse.prune;

import io.yunxi.platform.muse.config.EvolutionConfig;
import io.yunxi.platform.muse.prune.SkillPruner.PruneDecision;
import io.yunxi.platform.muse.prune.SkillPruner.SkillMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultSkillPruner 单元测试：验证低使用率归档 + 相似度合并逻辑。
 */
class DefaultSkillPrunerTest {

    private DefaultSkillPruner pruner;
    private EvolutionConfig config;

    @BeforeEach
    void setUp() {
        config = new EvolutionConfig();
        config.getPruner().setSimilarityThreshold(0.85);
        config.getPruner().setMinUsage(1);
        pruner = new DefaultSkillPruner(config);
    }

    @Test
    void testLowUsageArchived() {
        List<SkillMeta> skills = List.of(
                meta("low_skill", 0, "some content"),
                meta("normal_skill", 10, "other content")
        );

        List<PruneDecision> decisions = pruner.review(skills);
        assertFalse(decisions.isEmpty());

        boolean hasArchive = decisions.stream()
                .anyMatch(d -> d.getName().equals("low_skill")
                        && d.getAction() == SkillPruner.Action.ARCHIVE);
        assertTrue(hasArchive, "使用次数为 0 的技能应被归档");
    }

    @Test
    void testNoLowUsageWhenAboveMin() {
        config.getPruner().setMinUsage(5);
        pruner = new DefaultSkillPruner(config);

        List<SkillMeta> skills = List.of(
                meta("used_skill", 5, "content"),
                meta("busy_skill", 100, "other content")
        );

        List<PruneDecision> decisions = pruner.review(skills);

        boolean hasArchive = decisions.stream()
                .anyMatch(d -> d.getAction() == SkillPruner.Action.ARCHIVE);
        assertFalse(hasArchive, "使用次数 >= min 的技能不应被归档");
    }

    @Test
    void testHighlySimilarSkillsMerged() {
        String content = "数据分析技能：提供均值、标准差、分位数等统计指标计算功能";
        List<SkillMeta> skills = List.of(
                meta("data_stats_v1", 10, content),
                meta("data_stats_v2", 5, content)  // 完全相同，使用率较低
        );

        List<PruneDecision> decisions = pruner.review(skills);

        boolean hasMerge = decisions.stream()
                .anyMatch(d -> d.getAction() == SkillPruner.Action.MERGE
                        && d.getName().equals("data_stats_v2"));
        assertTrue(hasMerge, "完全相同的技能应触发合并");
    }

    @Test
    void testDifferentSkillsNotMerged() {
        List<SkillMeta> skills = List.of(
                meta("data_stats", 10, "数据分析技能：提供均值、标准差、分位数等统计指标"),
                meta("text_clean", 10, "文本清洗技能：去除空白字符、替换特殊符号、归一化大小写")
        );

        List<PruneDecision> decisions = pruner.review(skills);

        boolean hasMerge = decisions.stream()
                .anyMatch(d -> d.getAction() == SkillPruner.Action.MERGE);
        assertFalse(hasMerge, "完全不同的技能不应合并");
    }

    @Test
    void testEmptySkillList() {
        List<SkillMeta> skills = List.of();
        List<PruneDecision> decisions = pruner.review(skills);
        assertTrue(decisions.isEmpty());
    }

    private SkillMeta meta(String name, int usage, String content) {
        SkillMeta m = new SkillMeta();
        m.setName(name);
        m.setUsageCount(usage);
        m.setContent(content);
        return m;
    }
}
