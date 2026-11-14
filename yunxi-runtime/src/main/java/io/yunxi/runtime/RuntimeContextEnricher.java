package io.yunxi.runtime;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 运行时平面。由 {@link ExecutionFacade} 构建的 {@link RuntimeContext} 的每调用富化。与框架 {@code MiddlewareBase}
 * （协议无关、在装配期挂载）不同，富化器在每次执行时运行，且可以读取 {@link AgentCommand}（例如写入画像 / traceparent / 外部身份）。
 * 例子：注入已解析用户画像的画像富化器、传播 traceparent 的链路追踪富化器。
 */
@FunctionalInterface
public interface RuntimeContextEnricher {

    void enrich(RuntimeContext.Builder builder, AgentCommand command);
}
