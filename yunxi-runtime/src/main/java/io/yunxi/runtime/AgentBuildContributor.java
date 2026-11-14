package io.yunxi.runtime;

import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.definition.AgentSpec;
import org.springframework.lang.Nullable;

/**
 * 构建期中间件贡献点。允许产品平面（如 aistio 数据面事件流观测中间件）在 Agent 构建前注入横切中间件，
 * 而不破坏「运行时唯一装配点」边界：运行时只认识 MiddlewareBase 与 AgentSpec，不感知任何具体集成实现。
 *
 * <p>实现类由产品平面（fabric）提供，通常按 Agent 实例化有状态适配器并记住实例，以便在 Agent 构建完成后
 * 用同一实例完成后续接线（见 fabric 的 aistio 集成）。</p>
 */
public interface AgentBuildContributor {

    /** 返回该 Agent 需要在 build 期额外注册的横切中间件；不贡献则返回 null。 */
    @Nullable
    default MiddlewareBase buildTimeMiddleware(AgentSpec spec) {
        return null;
    }
}
