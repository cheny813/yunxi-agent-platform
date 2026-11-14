package io.yunxi.capability;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import java.util.Map;

/**
 * 能力平面。静态路由策略：从预加载的映射中把一个 Agent 解析为其配置的主模型 + 兜底模型。生产策略会参考负载 / 健康度 / 意图；
 * 运行时依赖的契约（入参 RoutedModel、协议无关）才是关键。
 */
public class DefaultModelRoutingPolicy implements ModelRoutingPolicy {

    private final Map<String, RoutedModel> routes;

    /** 注入预加载的静态路由映射（agentId → 路由结果）。 */
    public DefaultModelRoutingPolicy(Map<String, RoutedModel> routes) {
        this.routes = routes;
    }

    /** 按 agentId 命中预配置路由；未命中返回空候选序列（不抛异常，由上游决定是否拒绝）。 */
    @Override
    public RoutedModel route(String agentId, String intent, RuntimeContext ctx) {
        RoutedModel routed = routes.get(agentId);
        if (routed != null) {
            return routed;
        }
        return new RoutedModel(agentId, List.of());
    }
}
