package io.yunxi.runtime;

import io.agentscope.core.model.Model;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时平面。由一份静态配置模型映射支撑的 {@link ModelFactory}。适用于单实例与测试装配；生产工厂会针对模型注册表解析 id。
 */
public class MapModelFactory implements ModelFactory {

    private final Map<String, Model> models;

    public MapModelFactory(Map<String, Model> models) {
        this.models = new ConcurrentHashMap<>(models);
    }

    @Override
    public Model create(String modelId) {
        return models.get(modelId);
    }
}
