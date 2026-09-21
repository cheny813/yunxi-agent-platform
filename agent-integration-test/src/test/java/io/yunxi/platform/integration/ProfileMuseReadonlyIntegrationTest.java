package io.yunxi.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.agentscope.core.message.ToolResultState;

import io.yunxi.platform.AgentPlatformApplication;
import io.yunxi.platform.muse.config.EvolutionConfig;
import io.yunxi.platform.muse.prune.DefaultSkillPruner;
import io.yunxi.platform.muse.prune.SkillPruner;
import io.yunxi.platform.muse.prune.UsageProfile;
import io.yunxi.platform.muse.prune.UsageProfileAggregator;
import io.yunxi.platform.profile.RedisUserProfileStore;
import io.yunxi.platform.spi.profile.UserProfileProvider.Identity;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;
import io.yunxi.platform.trace.ReasoningSpan;
import io.yunxi.platform.trace.SpanKind;
import io.yunxi.platform.trace.TraceStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 「用户画像作为 MUSE 只读输入特征」端到端集成测试。
 *
 * <p>用<b>真实</b> RedisTraceStore + RedisUserProfileStore + UsageProfileAggregator +
 * DefaultSkillPruner，跑通完整链路：轨迹落库 → 画像落库 → 聚合出使用者画像 → 剪枝产出画像驱动判据。</p>
 *
 * <p>同时验证降级一致性：画像缺失 / 开关关闭时，剪枝行为与未引入本特性时一致。</p>
 *
 * <p>激活方式：{@code mvn -Pintegration test -Dtest=ProfileMuseReadonlyIntegrationTest}（需本地 Redis）。</p>
 */
@SpringBootTest(
        classes = {AgentPlatformApplication.class, IntegrationTestConfig.class},
        properties = {
                // 本测试只验证「轨迹 → 画像 → 剪枝」的存储与聚合链路，不调用 LLM；
                // Spring AI 的 OpenAI 自动配置在缺少 api-key 时会阻断上下文启动，故显式排除。
                "spring.ai.openai.api-key=test-not-used",
                "spring.autoconfigure.exclude=org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration"
        })
@DisplayName("画像 → MUSE 只读消费链路（端到端）")
class ProfileMuseReadonlyIntegrationTest {

    /**
     * 技能名按测试方法唯一：Redis 是共享存储，{@code recentTraceIds} 会枚举到历史轨迹，
     * 用唯一技能名把本测试的数据与历史数据隔离。
     */
    private final String skill = "e2e_profile_skill_" + System.nanoTime();

    @Autowired
    private TraceStore traceStore;

    @Autowired
    private RedisUserProfileStore profileStore;

    @Autowired
    private UsageProfileAggregator aggregator;

    @Autowired
    private EvolutionConfig evolutionConfig;

    /** 落一条含指定技能调用的轨迹（真实 Redis）。 */
    private void seedTrace(String userId, String sessionId, String skillName, ToolResultState status) {
        String traceId = userId + ":" + sessionId;
        traceStore.clear(traceId);
        traceStore.append(ReasoningSpan.closed(traceId, "tool-" + skillName, "evt-" + sessionId,
                SpanKind.TOOL_CALL, status, 12L, Map.of("toolName", skillName)));
    }

    /**
     * 清理本测试技能在共享 Redis 上可能残留的画像数据，保证断言可重复。
     *
     * <p>集成测试用真实 Redis，历史运行会留下同名用户画像；这里按本测试使用的用户前缀清理。</p>
     */
    @org.junit.jupiter.api.BeforeEach
    void cleanResidue() {
        for (String userId : List.of("e2e-u1", "e2e-u2", "e2e-u3", "e2e-solo", "e2e-enum")) {
            profileStore.saveProfile(userId, new UserProfile(userId, List.of(), Map.of(), Map.of(), 0L));
            // 清掉历史运行留下的轨迹：recentTraceIds 是全局枚举，
            // 不清会让上一轮的 traceId 混入本轮聚合，distinctUsers 虚高。
            for (String sid : List.of("s1", "s2", "s3")) {
                traceStore.clear(userId + ":" + sid);
            }
        }
    }

    /** 落一份画像（真实 Redis），带一个身份类别。 */
    private void seedProfile(String userId, String category, String displayName) {
        Identity identity = new Identity(displayName, displayName, category, 0.9, List.of(category), Map.of());
        profileStore.saveProfile(userId, new UserProfile(userId, List.of(identity), Map.of(), Map.of(),
                System.currentTimeMillis()));
    }

    @Test
    @DisplayName("多用户使用同一技能：聚合出使用者广度并撤销低使用率归档")
    void wideUsageRescindsArchiveEndToEnd() {
        // 三个不同用户各自用过该技能，画像类别分别为「职业」与「兴趣」
        seedTrace("e2e-u1", "s1", skill, ToolResultState.SUCCESS);
        seedTrace("e2e-u2", "s2", skill, ToolResultState.SUCCESS);
        seedTrace("e2e-u3", "s3", skill, ToolResultState.ERROR);
        seedProfile("e2e-u1", "职业", "工程师");
        seedProfile("e2e-u2", "职业", "数据分析师");
        seedProfile("e2e-u3", "兴趣", "跑步");

        UsageProfile up = aggregator.aggregate(skill);

        assertThat(up).as("聚合器应读到画像维度").isNotNull();
        assertThat(up.getDistinctUsers()).isEqualTo(3);
        assertThat(up.getSuccessCalls()).isEqualTo(2);
        assertThat(up.getCategoryDistribution()).containsEntry("职业", 2).containsEntry("兴趣", 1);

        // 交给真实剪枝器：usageCount=1 < minUsage=2 本应归档，但广度达标被撤销
        evolutionConfig.getPruner().setMinUsage(2);
        evolutionConfig.getPruner().getProfileAware().setGeneralUsageThreshold(3);
        DefaultSkillPruner pruner = new DefaultSkillPruner(evolutionConfig);
        List<SkillPruner.PruneDecision> decisions =
                pruner.review(List.of(new SkillPruner.SkillMeta(skill, 1, null, up)));

        assertThat(decisions).as("多用户使用应撤销归档").isEmpty();
    }

    @Test
    @DisplayName("单一用户高频使用：标记过拟合（需泛化）")
    void singleUserHighFrequencyMarkedOverfitEndToEnd() {
        seedTrace("e2e-solo", "s1", skill, ToolResultState.SUCCESS);
        seedTrace("e2e-solo", "s2", skill, ToolResultState.SUCCESS);
        seedTrace("e2e-solo", "s3", skill, ToolResultState.SUCCESS);
        seedProfile("e2e-solo", "职业", "产品经理");

        UsageProfile up = aggregator.aggregate(skill);

        assertThat(up).isNotNull();
        assertThat(up.getDistinctUsers()).as("同一用户多轨迹应去重为 1").isEqualTo(1);

        evolutionConfig.getPruner().setMinUsage(1);
        evolutionConfig.getPruner().getProfileAware().setOverfitUsageThreshold(2);
        DefaultSkillPruner pruner = new DefaultSkillPruner(evolutionConfig);
        List<SkillPruner.PruneDecision> decisions =
                pruner.review(List.of(new SkillPruner.SkillMeta(skill, 10, null, up)));

        assertThat(decisions).anyMatch(d -> d.getAction() == SkillPruner.Action.OVERFIT);
    }

    @Test
    @DisplayName("画像判据关闭：剪枝行为退化为原有规则")
    void disabledDegradesToLegacyEndToEnd() {
        seedTrace("e2e-u1", "s1", skill, ToolResultState.SUCCESS);
        seedProfile("e2e-u1", "职业", "工程师");

        evolutionConfig.getPruner().getProfileAware().setEnabled(false);
        try {
            assertThat(aggregator.aggregate(skill)).as("开关关闭时不聚合").isNull();

            evolutionConfig.getPruner().setMinUsage(1);
            DefaultSkillPruner pruner = new DefaultSkillPruner(evolutionConfig);
            List<SkillPruner.PruneDecision> decisions =
                    pruner.review(List.of(new SkillPruner.SkillMeta(skill, 0, null, null)));

            assertThat(decisions).hasSize(1);
            assertThat(decisions.get(0).getAction()).isEqualTo(SkillPruner.Action.ARCHIVE);
        } finally {
            evolutionConfig.getPruner().getProfileAware().setEnabled(true);
        }
    }

    @Test
    @DisplayName("轨迹枚举能力可用：落库的轨迹可被枚举到")
    void recentTraceIdsWorksWithRealRedis() {
        seedTrace("e2e-enum", "s1", skill, ToolResultState.SUCCESS);

        List<String> ids = traceStore.recentTraceIds(100);

        assertThat(ids).contains("e2e-enum:s1");
    }
}
