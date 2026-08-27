package io.yunxi.platform.intent;

import java.util.List;

/**
 * 路由建议（advisory，非强制）。仅进观测日志，不参与路由决策。
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record RouteHint(
        String suggestedAgent,
        List<String> suggestedExperts,
        List<String> toolGroups,
        List<String> skills,
        double score) {

    public static RouteHint empty() {
        return new RouteHint(null, List.of(), List.of(), List.of(), 0.0);
    }

    public boolean isEmpty() {
        return suggestedAgent == null;
    }
}
