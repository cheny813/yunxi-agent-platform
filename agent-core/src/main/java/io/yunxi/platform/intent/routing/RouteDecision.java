package io.yunxi.platform.intent.routing;

import java.util.List;

import io.agentscope.core.agent.Agent;

/**
 * 意图路由决策结果。
 *
 * <p>advisory：调用方仅在 {@code adopted() == true} 时改道，否则走默认 Agent 解析链。
 * 目标 agent 已通过存在性校验；experts / toolGroups / skills 为 {@code RouteHint}
 * 注入建议（当前由调用方透传）。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record RouteDecision(
        Agent agent,
        List<String> experts,
        List<String> toolGroups,
        List<String> skills,
        boolean adopted) {

    /** 未改道决策（开关关闭 / 无 hint / 分数不足 / 目标 agent 不存在） */
    public static RouteDecision notAdopted() {
        return new RouteDecision(null, List.of(), List.of(), List.of(), false);
    }

    /** 改道决策 */
    public static RouteDecision adopted(Agent agent, List<String> experts,
            List<String> toolGroups, List<String> skills) {
        return new RouteDecision(agent, experts, toolGroups, skills, true);
    }
}
