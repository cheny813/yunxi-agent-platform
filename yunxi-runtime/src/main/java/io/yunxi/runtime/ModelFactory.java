package io.yunxi.runtime;

import io.agentscope.core.model.Model;

/**
 * 运行时平面。把一个模型 id（来自 {@link io.yunxi.capability.RoutedModel}）解析为存活的框架 {@link Model}。
 * 实现可由配置、模型注册表或远程模型网关支撑。当 id 未知时返回 {@code null}。
 */
public interface ModelFactory {

    Model create(String modelId);
}
