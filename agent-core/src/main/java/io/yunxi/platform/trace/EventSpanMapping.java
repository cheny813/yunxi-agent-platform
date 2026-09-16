package io.yunxi.platform.trace;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import io.agentscope.core.event.AgentEventType;

/**
 * 框架事件到轨迹节点的映射。
 *
 * <p>定义每个事件类型产生的节点种类、快照阶段与配对键类型。节点稳定标识由配对键推导，
 * 使同一逻辑节点在多次快照间保持一致，从而支持按稳定标识分组还原。</p>
 *
 * <p>配对键分三类：回复标识（一次模型回复内唯一）、块标识（一个内容块内唯一）、
 * 工具调用标识（一次工具调用内唯一）。少数事件不携带任何配对键，其节点标识只能由
 * 事件自身位置推导，因此单独标记为不可配对。</p>
 *
 * @author yunxi-agent-platform
 */
public final class EventSpanMapping {

    /**
     * 配对键类型。
     */
    public enum PairKey {
        /** 回复标识 */
        REPLY_ID,
        /** 块标识 */
        BLOCK_ID,
        /** 工具调用标识 */
        TOOL_CALL_ID,
        /** 无配对键，节点不可跨快照关联 */
        NONE
    }

    /**
     * 单个事件类型的映射规则。
     *
     * @param kind     节点种类
     * @param snapshot 快照阶段
     * @param pairKey  配对键类型
     */
    public record Rule(SpanKind kind, Snapshot snapshot, PairKey pairKey) {
    }

    private static final Map<AgentEventType, Rule> RULES = new EnumMap<>(AgentEventType.class);

    static {
        // ── 回合边界 ──────────────────────────────────────────
        put(AgentEventType.AGENT_START, SpanKind.TURN, Snapshot.OPEN, PairKey.REPLY_ID);
        put(AgentEventType.AGENT_END, SpanKind.TURN, Snapshot.CLOSED, PairKey.REPLY_ID);

        // ── 模型调用 ──────────────────────────────────────────
        put(AgentEventType.MODEL_CALL_START, SpanKind.MODEL_CALL, Snapshot.OPEN, PairKey.REPLY_ID);
        put(AgentEventType.MODEL_CALL_END, SpanKind.MODEL_CALL, Snapshot.CLOSED, PairKey.REPLY_ID);

        // ── 文本块 ────────────────────────────────────────────
        put(AgentEventType.TEXT_BLOCK_START, SpanKind.TEXT, Snapshot.OPEN, PairKey.BLOCK_ID);
        put(AgentEventType.TEXT_BLOCK_DELTA, SpanKind.TEXT, Snapshot.DELTA, PairKey.BLOCK_ID);
        put(AgentEventType.TEXT_BLOCK_END, SpanKind.TEXT, Snapshot.CLOSED, PairKey.BLOCK_ID);

        // ── 思考块 ────────────────────────────────────────────
        put(AgentEventType.THINKING_BLOCK_START, SpanKind.REASONING, Snapshot.OPEN, PairKey.BLOCK_ID);
        put(AgentEventType.THINKING_BLOCK_DELTA, SpanKind.REASONING, Snapshot.DELTA, PairKey.BLOCK_ID);
        put(AgentEventType.THINKING_BLOCK_END, SpanKind.REASONING, Snapshot.CLOSED, PairKey.BLOCK_ID);

        // ── 数据块 ────────────────────────────────────────────
        put(AgentEventType.DATA_BLOCK_START, SpanKind.DATA, Snapshot.OPEN, PairKey.BLOCK_ID);
        put(AgentEventType.DATA_BLOCK_DELTA, SpanKind.DATA, Snapshot.DELTA, PairKey.BLOCK_ID);
        put(AgentEventType.DATA_BLOCK_END, SpanKind.DATA, Snapshot.CLOSED, PairKey.BLOCK_ID);

        // ── 工具调用 ──────────────────────────────────────────
        put(AgentEventType.TOOL_CALL_START, SpanKind.TOOL_CALL, Snapshot.OPEN, PairKey.TOOL_CALL_ID);
        put(AgentEventType.TOOL_CALL_DELTA, SpanKind.TOOL_CALL, Snapshot.DELTA, PairKey.TOOL_CALL_ID);
        put(AgentEventType.TOOL_CALL_END, SpanKind.TOOL_CALL, Snapshot.CLOSED, PairKey.TOOL_CALL_ID);

        // ── 工具结果：与工具调用同属一个节点，不另开节点 ────────
        put(AgentEventType.TOOL_RESULT_START, SpanKind.TOOL_CALL, Snapshot.DELTA, PairKey.TOOL_CALL_ID);
        put(AgentEventType.TOOL_RESULT_TEXT_DELTA, SpanKind.TOOL_CALL, Snapshot.DELTA, PairKey.TOOL_CALL_ID);
        put(AgentEventType.TOOL_RESULT_DATA_DELTA, SpanKind.TOOL_CALL, Snapshot.DELTA, PairKey.TOOL_CALL_ID);
        put(AgentEventType.TOOL_RESULT_END, SpanKind.TOOL_CALL, Snapshot.CLOSED, PairKey.TOOL_CALL_ID);

        // ── 人机交互 ──────────────────────────────────────────
        put(AgentEventType.REQUIRE_USER_CONFIRM, SpanKind.HITL, Snapshot.OPEN, PairKey.REPLY_ID);
        put(AgentEventType.USER_CONFIRM_RESULT, SpanKind.HITL, Snapshot.CLOSED, PairKey.REPLY_ID);
        put(AgentEventType.REQUIRE_EXTERNAL_EXECUTION, SpanKind.HITL, Snapshot.OPEN, PairKey.REPLY_ID);
        put(AgentEventType.EXTERNAL_EXECUTION_RESULT, SpanKind.HITL, Snapshot.CLOSED, PairKey.REPLY_ID);

        // ── 子代理暴露 ────────────────────────────────────────
        put(AgentEventType.SUBAGENT_EXPOSED, SpanKind.SUBAGENT, Snapshot.OPEN, PairKey.NONE);

        // ── 控制信号 ──────────────────────────────────────────
        put(AgentEventType.EXCEED_MAX_ITERS, SpanKind.CONTROL, Snapshot.CLOSED, PairKey.REPLY_ID);
        put(AgentEventType.ALL_TOOLS_DENIED, SpanKind.CONTROL, Snapshot.CLOSED, PairKey.NONE);
        put(AgentEventType.REQUEST_STOP, SpanKind.CONTROL, Snapshot.CLOSED, PairKey.NONE);

        // ── 结果与提示 ────────────────────────────────────────
        put(AgentEventType.AGENT_RESULT, SpanKind.CONTROL, Snapshot.CLOSED, PairKey.NONE);
        put(AgentEventType.HINT_BLOCK, SpanKind.CUSTOM, Snapshot.CLOSED, PairKey.BLOCK_ID);

        // ── 自定义 ────────────────────────────────────────────
        put(AgentEventType.CUSTOM, SpanKind.CUSTOM, Snapshot.CLOSED, PairKey.NONE);
    }

    private static void put(AgentEventType type, SpanKind kind, Snapshot snapshot, PairKey pairKey) {
        RULES.put(type, new Rule(kind, snapshot, pairKey));
    }

    private EventSpanMapping() {
    }

    /**
     * 查询事件类型的映射规则。
     *
     * @param type 事件类型
     * @return 映射规则；未登记时返回 null
     */
    public static Rule ruleOf(AgentEventType type) {
        return type == null ? null : RULES.get(type);
    }

    /**
     * 已登记的映射规则，供覆盖度校验使用。
     *
     * @return 事件类型到规则的只读映射
     */
    public static Map<AgentEventType, Rule> allRules() {
        return Map.copyOf(RULES);
    }

    /**
     * 未纳入映射的事件类型。
     *
     * <p>用于校验映射覆盖度：此集合应始终为空，否则说明框架新增了事件类型而未同步归集规则。</p>
     *
     * @return 未覆盖的事件类型
     */
    public static Set<AgentEventType> uncoveredTypes() {
        Set<AgentEventType> uncovered = java.util.EnumSet.noneOf(AgentEventType.class);
        for (AgentEventType type : AgentEventType.values()) {
            if (!RULES.containsKey(type)) {
                uncovered.add(type);
            }
        }
        return uncovered;
    }
}
