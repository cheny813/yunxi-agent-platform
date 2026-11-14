package io.yunxi.intelligence.muse.prune;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.intelligence.muse.config.EvolutionConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MUSE 剪枝器核心判定验证（无大模型，离线）：{@link DefaultSkillPruner} 的三遍规则是最易错处——
 * 画像翻案「只修正不新增删除」的语义必须守住，否则会误杀小众领域核心技能。
 */
class DefaultSkillPrunerTest {

    private EvolutionConfig defaultConfig() {
        return new EvolutionConfig(); // 默认值：minUsage=1, similarityThreshold=0.85, ProfileAware 开, general=3, overfit=5
    }

    private boolean has(List<SkillPruner.PruneDecision> ds, String name, SkillPruner.Action a) {
        return ds.stream().anyMatch(d -> d.getName().equals(name) && d.getAction() == a);
    }

    @Test
    void lowUsage_archived() {
        DefaultSkillPruner p = new DefaultSkillPruner(defaultConfig());
        SkillPruner.SkillMeta s = new SkillPruner.SkillMeta("s1", 0, "content");
        assertTrue(has(p.review(List.of(s)), "s1", SkillPruner.Action.ARCHIVE), "使用次数<minUsage 必须归档");
    }

    @Test
    void lowUsageButManyUsers_profileReversalRemovesArchive() {
        DefaultSkillPruner p = new DefaultSkillPruner(defaultConfig());
        UsageProfile up = new UsageProfile();
        up.setDistinctUsers(5);
        up.setSuccessCalls(5);
        SkillPruner.SkillMeta s = new SkillPruner.SkillMeta("s1", 0, "content", up);
        assertTrue(!has(p.review(List.of(s)), "s1", SkillPruner.Action.ARCHIVE),
                "多用户使用后画像判据必须撤销低使用率归档（通用价值）");
    }

    @Test
    void singleUserHighUsage_markedOverfit() {
        DefaultSkillPruner p = new DefaultSkillPruner(defaultConfig());
        UsageProfile up = new UsageProfile();
        up.setDistinctUsers(1);
        up.setSuccessCalls(10);
        SkillPruner.SkillMeta s = new SkillPruner.SkillMeta("s1", 10, "content", up);
        assertTrue(has(p.review(List.of(s)), "s1", SkillPruner.Action.OVERFIT),
                "单一用户高频必须为过拟合标记（只标记不删除）");
    }

    @Test
    void noProfileData_keepsOriginalArchive() {
        DefaultSkillPruner p = new DefaultSkillPruner(defaultConfig());
        SkillPruner.SkillMeta s = new SkillPruner.SkillMeta("s1", 0, "content"); // usageProfile 缺省 null
        assertTrue(has(p.review(List.of(s)), "s1", SkillPruner.Action.ARCHIVE),
                "无画像数据时保持原有低使用率归档判定");
    }

    @Test
    void profileAwareDisabled_keepsOriginalArchiveDespiteManyUsers() {
        EvolutionConfig c = defaultConfig();
        c.getPruner().getProfileAware().setEnabled(false);
        DefaultSkillPruner p = new DefaultSkillPruner(c);
        UsageProfile up = new UsageProfile();
        up.setDistinctUsers(5);
        SkillPruner.SkillMeta s = new SkillPruner.SkillMeta("s1", 0, "content", up);
        assertTrue(has(p.review(List.of(s)), "s1", SkillPruner.Action.ARCHIVE),
                "画像判据关闭时即便多用户也不翻案，行为回退到无画像版本");
    }

    @Test
    void identicalContent_merged() {
        DefaultSkillPruner p = new DefaultSkillPruner(defaultConfig());
        SkillPruner.SkillMeta a = new SkillPruner.SkillMeta("a", 10, "same content here");
        SkillPruner.SkillMeta b = new SkillPruner.SkillMeta("b", 10, "same content here");
        assertTrue(p.review(List.of(a, b)).stream().anyMatch(d -> d.getAction() == SkillPruner.Action.MERGE),
                "相同正文必须触发相似度合并");
    }
}
