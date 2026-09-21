package io.yunxi.platform.muse.prune;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.yunxi.platform.muse.config.EvolutionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DefaultSkillPruner 的画像驱动判据测试：撤销归档、过拟合标记，以及与原有规则的降级一致性。
 */
@DisplayName("画像驱动剪枝判据")
class ProfileAwarePrunerTest {

    private EvolutionConfig config;
    private DefaultSkillPruner pruner;

    @BeforeEach
    void setUp() {
        config = new EvolutionConfig();
        pruner = new DefaultSkillPruner(config);
    }

    private static UsageProfile profile(int distinctUsers) {
        return new UsageProfile(distinctUsers, Map.of(), distinctUsers);
    }

    @Test
    @DisplayName("无画像数据：行为与原有规则一致（低使用率归档）")
    void noProfileBehavesAsBefore() {
        config.getPruner().setMinUsage(1);
        List<SkillPruner.SkillMeta> skills = List.of(
                new SkillPruner.SkillMeta("dead_skill", 0, null));

        List<SkillPruner.PruneDecision> decisions = pruner.review(skills);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getAction()).isEqualTo(SkillPruner.Action.ARCHIVE);
    }

    @Test
    @DisplayName("画像判据关闭：与无画像数据一致")
    void disabledBehavesAsBefore() {
        config.getPruner().setMinUsage(1);
        config.getPruner().getProfileAware().setEnabled(false);
        List<SkillPruner.SkillMeta> skills = List.of(
                new SkillPruner.SkillMeta("skill_a", 0, null, profile(5)));

        List<SkillPruner.PruneDecision> decisions = pruner.review(skills);

        // 关闭后不撤销归档，行为与改造前一致
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getAction()).isEqualTo(SkillPruner.Action.ARCHIVE);
    }

    @Test
    @DisplayName("使用者广度达标：撤销低使用率归档（多用户证明通用价值）")
    void wideUsageRescindsArchive() {
        config.getPruner().setMinUsage(2);
        config.getPruner().getProfileAware().setGeneralUsageThreshold(3);
        // usageCount=1 < minUsage=2 → 第一遍判归档；但画像显示 5 个不同用户用过
        List<SkillPruner.SkillMeta> skills = List.of(
                new SkillPruner.SkillMeta("general_skill", 1, null, profile(5)));

        List<SkillPruner.PruneDecision> decisions = pruner.review(skills);

        assertThat(decisions).isEmpty();
    }

    @Test
    @DisplayName("单一用户高频：标记过拟合（需泛化），不归档")
    void singleUserHighFrequencyMarkedOverfit() {
        config.getPruner().setMinUsage(1);
        config.getPruner().getProfileAware().setOverfitUsageThreshold(5);
        // usageCount=10 >= 5 且 distinctUsers=1 → OVERFIT
        List<SkillPruner.SkillMeta> skills = List.of(
                new SkillPruner.SkillMeta("personal_skill", 10, null, profile(1)));

        List<SkillPruner.PruneDecision> decisions = pruner.review(skills);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getAction()).isEqualTo(SkillPruner.Action.OVERFIT);
        assertThat(decisions.get(0).getName()).isEqualTo("personal_skill");
    }

    @Test
    @DisplayName("单一用户低频：不标记过拟合（样本不足）")
    void singleUserLowFrequencyNotMarked() {
        config.getPruner().setMinUsage(1);
        config.getPruner().getProfileAware().setOverfitUsageThreshold(5);
        // usageCount=2 < 5 → 不标记
        List<SkillPruner.SkillMeta> skills = List.of(
                new SkillPruner.SkillMeta("new_skill", 2, null, profile(1)));

        List<SkillPruner.PruneDecision> decisions = pruner.review(skills);

        assertThat(decisions).isEmpty();
    }

    @Test
    @DisplayName("多用户低频：不标记过拟合（distinctUsers > 1）")
    void multiUserLowFrequencyNotMarked() {
        config.getPruner().setMinUsage(1);
        config.getPruner().getProfileAware().setOverfitUsageThreshold(5);
        List<SkillPruner.SkillMeta> skills = List.of(
                new SkillPruner.SkillMeta("shared_skill", 6, null, profile(3)));

        List<SkillPruner.PruneDecision> decisions = pruner.review(skills);

        assertThat(decisions).isEmpty();
    }

    @Test
    @DisplayName("画像判据不影响相似度合并（第三遍行为不变）")
    void similarityMergeUnaffected() {
        config.getPruner().setMinUsage(0); // 不触发归档
        config.getPruner().setSimilarityThreshold(0.5);
        String content = "数据描述性统计技能：计算均值、中位数、标准差等指标";
        List<SkillPruner.SkillMeta> skills = List.of(
                new SkillPruner.SkillMeta("s1", 5, content, profile(5)),
                new SkillPruner.SkillMeta("s2", 3, content, profile(3)));

        List<SkillPruner.PruneDecision> decisions = pruner.review(skills);

        assertThat(decisions).anyMatch(d -> d.getAction() == SkillPruner.Action.MERGE);
    }
}
