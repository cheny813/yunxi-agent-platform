package io.yunxi.capability;

import io.agentscope.core.model.Model;

/**
 * 把框架模型 id（provider:model）解析为可调用 {@link Model} 的能力抽象。
 *
 * <p>置于能力层以避免与运行时的 {@code ModelFactory} 形成模块循环依赖；运行层提供实现
 * （包装 AgentScope-Java 的 {@code ModelRegistry.resolve}）。自动选型精排器等需要按需解析模型时依赖本接口。
 */
@FunctionalInterface
public interface ModelResolver {

    /**
     * 解析模型 id 为 {@link Model}；不可解析（无对应 provider / 未配置）时返回 null。
     *
     * @param modelId 框架模型 id（provider:model）
     * @return 可调用模型，或 null
     */
    Model resolve(String modelId);
}
