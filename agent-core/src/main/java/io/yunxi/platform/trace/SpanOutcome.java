package io.yunxi.platform.trace;

import java.util.Map;

import io.agentscope.core.message.ToolResultState;

/**
 * 轨迹节点的终态判定。
 *
 * <p>终态取值直接复用框架的工具结果状态，不自造状态集合。此处只负责两类边界情形的判定：
 * 一是回合结束时仍存在未决的人机交互请求，二是外部协议中「已取消」这一结果在框架状态里的
 * 对应关系。</p>
 *
 * @author yunxi-agent-platform
 */
public final class SpanOutcome {

    /**
     * 未决：回合结束时仍有未被回应的人机交互请求等待处理。
     *
     * <p>该情形不是失败，也不是成功——请求已发出但尚未被处理，因此单独表达。取值复用框架的
     * 中断状态，与之同义：都在表达「流程未走到自然终点而停在此处」。</p>
     */
    public static final ToolResultState PENDING = ToolResultState.INTERRUPTED;

    /**
     * 外部协议结果到框架状态的映射。
     *
     * <p>外部协议表达四种结果：成功、失败、中断、已取消。框架状态没有「已取消」，
     * 因此「已取消」与「中断」落到同一取值。此处显式登记该映射，避免两者在各消费方
     * 各自判断时产生分歧。</p>
     */
    private static final Map<String, ToolResultState> EXTERNAL_OUTCOME_MAP = Map.of(
            "success", ToolResultState.SUCCESS,
            "error", ToolResultState.ERROR,
            "interrupt", ToolResultState.INTERRUPTED,
            "cancelled", ToolResultState.INTERRUPTED);

    private SpanOutcome() {
    }

    /**
     * 把外部协议的运行结果映射为框架状态。
     *
     * @param externalOutcome 外部结果名（大小写不敏感）
     * @return 对应的框架状态；无法识别时返回中断态
     */
    public static ToolResultState fromExternal(String externalOutcome) {
        if (externalOutcome == null) {
            return ToolResultState.INTERRUPTED;
        }
        return EXTERNAL_OUTCOME_MAP.getOrDefault(
                externalOutcome.trim().toLowerCase(), ToolResultState.INTERRUPTED);
    }

    /**
     * 判定回合结束时的终态。
     *
     * @param pendingHitlCount 结束时仍未决的人机交互请求数
     * @param failed           是否以失败结束
     * @return 终态
     */
    public static ToolResultState closingOutcome(int pendingHitlCount, boolean failed) {
        if (pendingHitlCount > 0) {
            return PENDING;
        }
        return failed ? ToolResultState.ERROR : ToolResultState.SUCCESS;
    }

    /**
     * 判断某状态是否表示「未走到自然终点」。
     *
     * @param status 状态
     * @return 未走到自然终点返回 true
     */
    public static boolean isUnfinished(ToolResultState status) {
        return status == ToolResultState.INTERRUPTED
                || status == ToolResultState.DENIED
                || status == ToolResultState.RUNNING;
    }
}
