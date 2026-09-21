package io.yunxi.platform.muse.prune;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import io.agentscope.core.message.ToolResultState;

import io.yunxi.platform.muse.config.EvolutionConfig;
import io.yunxi.platform.profile.RedisUserProfileStore;
import io.yunxi.platform.spi.profile.UserProfileProvider.Identity;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;
import io.yunxi.platform.trace.ReasoningSpan;
import io.yunxi.platform.trace.Snapshot;
import io.yunxi.platform.trace.SpanKind;
import io.yunxi.platform.trace.TraceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UsageProfileAggregator：画像只读聚合、使用者去重、降级与边界测试。
 */
@DisplayName("使用者画像聚合器")
class UsageProfileAggregatorTest {

    private TraceStore traceStore;
    private RedisUserProfileStore profileStore;
    private EvolutionConfig config;
    private UsageProfileAggregator aggregator;

    @BeforeEach
    void setUp() {
        traceStore = mock(TraceStore.class);
        profileStore = mock(RedisUserProfileStore.class);
        config = new EvolutionConfig();
        aggregator = new UsageProfileAggregator(traceStore, profileStore, config);
    }

    /** 构造一个技能调用的关闭快照（带 toolName 与终态）。 */
    private static ReasoningSpan toolCallSpan(String traceId, String toolName, ToolResultState status) {
        ReasoningSpan span = new ReasoningSpan(traceId, "k1", "e1", SpanKind.TOOL_CALL,
                null, 0, null, null, Snapshot.CLOSED, null, status, 10L,
                Map.of("toolName", toolName), System.currentTimeMillis());
        return span;
    }

    private static UserProfile profileOf(String userId, String... categories) {
        List<Identity> ids = java.util.Arrays.stream(categories)
                .map(c -> new Identity(c, c, c, 0.9, List.of(), Map.of()))
                .toList();
        return new UserProfile(userId, ids, Map.of(), Map.of(), 1L);
    }

    @Test
    @DisplayName("多用户命中：distinctUsers 正确去重，成功次数累计")
    void aggregatesDistinctUsersAndSuccess() {
        when(traceStore.recentTraceIds(anyInt()))
                .thenReturn(List.of("u1:s1", "u2:s2", "u3:s3"));
        when(traceStore.read("u1:s1")).thenReturn(List.of(toolCallSpan("u1:s1", "data_stats", ToolResultState.SUCCESS)));
        when(traceStore.read("u2:s2")).thenReturn(List.of(toolCallSpan("u2:s2", "data_stats", ToolResultState.SUCCESS)));
        when(traceStore.read("u3:s3")).thenReturn(List.of(toolCallSpan("u3:s3", "data_stats", ToolResultState.ERROR)));
        when(profileStore.getProfile(eq("u1"))).thenReturn(profileOf("u1", "职业"));
        when(profileStore.getProfile(eq("u2"))).thenReturn(profileOf("u2", "职业"));
        when(profileStore.getProfile(eq("u3"))).thenReturn(profileOf("u3", "兴趣"));

        UsageProfile up = aggregator.aggregate("data_stats");

        assertThat(up).isNotNull();
        assertThat(up.getDistinctUsers()).isEqualTo(3);
        assertThat(up.getSuccessCalls()).isEqualTo(2);
        assertThat(up.getCategoryDistribution()).containsEntry("职业", 2)
                .containsEntry("兴趣", 1);
    }

    @Test
    @DisplayName("同一用户多轨迹：distinctUsers 去重为 1（区分 1 人多次 vs 多人各一次）")
    void sameUserMultipleTracesCountedOnce() {
        when(traceStore.recentTraceIds(anyInt()))
                .thenReturn(List.of("u1:s1", "u1:s2", "u1:s3"));
        when(traceStore.read("u1:s1")).thenReturn(List.of(toolCallSpan("u1:s1", "skill_x", ToolResultState.SUCCESS)));
        when(traceStore.read("u1:s2")).thenReturn(List.of(toolCallSpan("u1:s2", "skill_x", ToolResultState.SUCCESS)));
        when(traceStore.read("u1:s3")).thenReturn(List.of(toolCallSpan("u1:s3", "skill_x", ToolResultState.SUCCESS)));
        when(profileStore.getProfile(eq("u1"))).thenReturn(profileOf("u1", "职业"));

        UsageProfile up = aggregator.aggregate("skill_x");

        assertThat(up).isNotNull();
        assertThat(up.getDistinctUsers()).isEqualTo(1);
        assertThat(up.getSuccessCalls()).isEqualTo(3);
        assertThat(up.getCategoryDistribution()).containsEntry("职业", 1);
    }

    @Test
    @DisplayName("技能未被任何轨迹命中：返回 null（降级）")
    void noHitReturnsNull() {
        when(traceStore.recentTraceIds(anyInt())).thenReturn(List.of("u1:s1"));
        when(traceStore.read("u1:s1")).thenReturn(List.of(toolCallSpan("u1:s1", "other_skill", ToolResultState.SUCCESS)));

        assertThat(aggregator.aggregate("data_stats")).isNull();
    }

    @Test
    @DisplayName("无轨迹：返回 null（降级）")
    void noTracesReturnsNull() {
        when(traceStore.recentTraceIds(anyInt())).thenReturn(List.of());

        assertThat(aggregator.aggregate("data_stats")).isNull();
    }

    @Test
    @DisplayName("开关关闭：返回 null，不读取轨迹")
    void disabledReturnsNull() {
        config.getPruner().getProfileAware().setEnabled(false);

        assertThat(aggregator.aggregate("data_stats")).isNull();
        org.mockito.Mockito.verify(traceStore, org.mockito.Mockito.never()).recentTraceIds(anyInt());
    }

    @Test
    @DisplayName("轨迹标识无使用者身份（无冒号）：跳过该轨迹")
    void traceWithoutUserSkipped() {
        when(traceStore.recentTraceIds(anyInt())).thenReturn(List.of("sesion-only"));
        when(traceStore.read("sesion-only")).thenReturn(List.of(toolCallSpan("sesion-only", "data_stats", ToolResultState.SUCCESS)));

        assertThat(aggregator.aggregate("data_stats")).isNull();
    }

    @Test
    @DisplayName("画像缺失：仍统计使用者数，类别分布为空（保守降级）")
    void missingProfileStillCountsUsers() {
        when(traceStore.recentTraceIds(anyInt())).thenReturn(List.of("u1:s1", "u2:s2"));
        when(traceStore.read("u1:s1")).thenReturn(List.of(toolCallSpan("u1:s1", "data_stats", ToolResultState.SUCCESS)));
        when(traceStore.read("u2:s2")).thenReturn(List.of(toolCallSpan("u2:s2", "data_stats", ToolResultState.SUCCESS)));
        when(profileStore.getProfile(eq("u1"))).thenReturn(null);
        when(profileStore.getProfile(eq("u2"))).thenReturn(null);

        UsageProfile up = aggregator.aggregate("data_stats");

        assertThat(up).isNotNull();
        assertThat(up.getDistinctUsers()).isEqualTo(2);
        assertThat(up.getCategoryDistribution()).isEmpty();
    }

    @Test
    @DisplayName("存储异常：返回 null，不抛出")
    void storeFailureReturnsNull() {
        when(traceStore.recentTraceIds(anyInt())).thenThrow(new RuntimeException("redis down"));

        assertThat(aggregator.aggregate("data_stats")).isNull();
    }

    @Test
    @DisplayName("技能名为空：返回 null")
    void blankSkillReturnsNull() {
        assertThat(aggregator.aggregate(null)).isNull();
        assertThat(aggregator.aggregate("  ")).isNull();
    }
}
