package io.yunxi.platform.framework.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;

/**
 * 知识库创建器 SPI 接口
 *
 * <p>
 * 每种知识库类型实现此接口，通过 Spring 组件扫描自动发现。
 * 新增知识库类型只需添加新的 {@code @Component} 实现，无需修改已有代码。
 * </p>
 *
 * <h3>扩展示例</h3>
 * <pre>{@code
 * @Component
 * public class PgVectorKnowledgeCreator implements KnowledgeCreator {
 *     &#64;Override
 *     public String getType() { return "pgvector"; }
 *
 *     &#64;Override
 *     public Knowledge create(KnowledgeBaseConfig config) {
 *         // 使用 AgentScope SDK 的 Builder API 创建
 *         return PgVectorKnowledge.builder()
 *                 .config(...)
 *                 .build();
 *     }
 * }
 * }</pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface KnowledgeCreator {

    /**
     * 获取支持的知识库类型标识
     * <p>与 {@code agentscope.yml} 中 {@code knowledge-bases.*.type} 对应。</p>
     *
     * @return 类型标识，如 "bailian"、"dify"、"ragflow"、"simple"、"haystack"
     */
    String getType();

    /**
     * 根据配置创建 Knowledge 实例
     *
     * @param config 知识库配置（来自 {@code agentscope.yml} 的 {@code knowledge-bases.*} 节点）
     * @return Knowledge 实例
     * @throws IllegalArgumentException 配置参数不合法时抛出
     */
    Knowledge create(KnowledgeBaseConfig config);

    /**
     * 默认是否启用（当配置中无 explicit enabled 时的默认行为）
     *
     * @return true 表示该类型默认启用
     */
    default boolean isEnabledByDefault() {
        return false;
    }
}