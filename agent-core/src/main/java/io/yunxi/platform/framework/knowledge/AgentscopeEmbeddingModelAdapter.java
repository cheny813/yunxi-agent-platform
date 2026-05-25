package io.yunxi.platform.framework.knowledge;

import io.yunxi.platform.framework.embedding.EmbeddingService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 将项目 {@link EmbeddingService} 适配为 AgentScope SDK 的 EmbeddingModel
 *
 * <p>
 * 避免重复实现 Ollama/DashScope/OpenAI 等嵌入逻辑，复用项目已有的嵌入管道。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
public class AgentscopeEmbeddingModelAdapter {

    private final EmbeddingService embeddingService;
    private final Object adaptedInstance;

    /**
     * 创建适配器并通过反射适配为 AgentScope EmbeddingModel
     *
     * @param embeddingService 项目已有的嵌入服务
     */
    public AgentscopeEmbeddingModelAdapter(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        this.adaptedInstance = createProxy();
    }

    /**
     * 获取适配后的 AgentScope EmbeddingModel 实例
     *
     * @return EmbeddingModel 实例（实际类型为 {@code io.agentscope.core.rag.embedding.EmbeddingModel}）
     */
    public Object getAdaptedInstance() {
        return adaptedInstance;
    }

    /**
     * 通过动态代理创建 EmbeddingModel 实现
     * <p>
     * 使用 Java 动态代理适配 EmbeddingModel 接口，避免直接依赖 AgentScope 的 EmbeddingModel 类。
     * </p>
     */
    private Object createProxy() {
        try {
            Class<?> embeddingModelClass = Class.forName("io.agentscope.core.rag.embedding.EmbeddingModel");

            return java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{embeddingModelClass},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "embed":
                                if (args != null && args.length > 0 && args[0] instanceof String) {
                                    List<Float> result = embeddingService.embed((String) args[0]);
                                    return result != null ? result : new ArrayList<>();
                                }
                                return new ArrayList<Float>();
                            case "dimension":
                                return embeddingService.getDimension();
                            case "toString":
                                return "AgentscopeEmbeddingModelAdapter{provider="
                                        + embeddingService.getProviderName() + "}";
                            default:
                                if (method.isDefault()) {
                                    return java.lang.invoke.MethodHandles.lookup()
                                            .in(method.getDeclaringClass())
                                            .unreflectSpecial(method, method.getDeclaringClass())
                                            .bindTo(proxy)
                                            .invokeWithArguments(args);
                                }
                                return null;
                        }
                    }
            );
        } catch (ClassNotFoundException e) {
            log.warn("AgentScope EmbeddingModel 类不存在，SimpleKnowledge 可能无法使用: {}", e.getMessage());
            return null;
        }
    }
}