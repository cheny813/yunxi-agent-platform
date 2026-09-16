package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.ToolResultState;

/**
 * SpanOutcome：未决态与外部结果映射测试。
 */
@DisplayName("SpanOutcome 终态判定")
class SpanOutcomeTest {

    @Test
    @DisplayName("回合结束时存在未决人机交互：终态为未决")
    void pendingHitlYieldsPending() {
        ToolResultState outcome = SpanOutcome.closingOutcome(2, false);

        assertThat(outcome).isEqualTo(SpanOutcome.PENDING);
        assertThat(outcome).isEqualTo(ToolResultState.INTERRUPTED);
    }

    @Test
    @DisplayName("未决优先于失败：同时存在时取未决")
    void pendingTakesPrecedenceOverFailure() {
        assertThat(SpanOutcome.closingOutcome(1, true)).isEqualTo(SpanOutcome.PENDING);
    }

    @Test
    @DisplayName("无未决且未失败：终态为成功")
    void normalCompletionYieldsSuccess() {
        assertThat(SpanOutcome.closingOutcome(0, false)).isEqualTo(ToolResultState.SUCCESS);
    }

    @Test
    @DisplayName("无未决但失败：终态为失败")
    void failedCompletionYieldsError() {
        assertThat(SpanOutcome.closingOutcome(0, true)).isEqualTo(ToolResultState.ERROR);
    }

    @Test
    @DisplayName("外部结果映射：成功 / 失败 / 中断各自对应")
    void externalOutcomeMapping() {
        assertThat(SpanOutcome.fromExternal("success")).isEqualTo(ToolResultState.SUCCESS);
        assertThat(SpanOutcome.fromExternal("error")).isEqualTo(ToolResultState.ERROR);
        assertThat(SpanOutcome.fromExternal("interrupt")).isEqualTo(ToolResultState.INTERRUPTED);
    }

    @Test
    @DisplayName("外部结果映射：已取消降级为中断态，不新增状态")
    void cancelledDowngradesToInterrupted() {
        ToolResultState cancelled = SpanOutcome.fromExternal("cancelled");

        assertThat(cancelled).isEqualTo(ToolResultState.INTERRUPTED);
        assertThat(ToolResultState.values())
                .extracting(Enum::name)
                .doesNotContain("CANCELLED");
    }

    @Test
    @DisplayName("已取消与中断落到同一状态，消费方可判定等价")
    void cancelledAndInterruptAreEquivalent() {
        assertThat(SpanOutcome.fromExternal("cancelled"))
                .isEqualTo(SpanOutcome.fromExternal("interrupt"));
    }

    @Test
    @DisplayName("外部结果大小写不敏感")
    void externalOutcomeCaseInsensitive() {
        assertThat(SpanOutcome.fromExternal("SUCCESS")).isEqualTo(ToolResultState.SUCCESS);
        assertThat(SpanOutcome.fromExternal(" Cancelled ")).isEqualTo(ToolResultState.INTERRUPTED);
    }

    @Test
    @DisplayName("未知或空的外部结果降级为中断态")
    void unknownOutcomeFallsBackToInterrupted() {
        assertThat(SpanOutcome.fromExternal("unknown")).isEqualTo(ToolResultState.INTERRUPTED);
        assertThat(SpanOutcome.fromExternal(null)).isEqualTo(ToolResultState.INTERRUPTED);
    }

    @Test
    @DisplayName("未完成判定：中断、被拒、进行中三个状态属于未走到自然终点")
    void unfinishedStates() {
        assertThat(SpanOutcome.isUnfinished(ToolResultState.INTERRUPTED)).isTrue();
        assertThat(SpanOutcome.isUnfinished(ToolResultState.DENIED)).isTrue();
        assertThat(SpanOutcome.isUnfinished(ToolResultState.RUNNING)).isTrue();
        assertThat(SpanOutcome.isUnfinished(ToolResultState.SUCCESS)).isFalse();
        assertThat(SpanOutcome.isUnfinished(ToolResultState.ERROR)).isFalse();
    }
}
