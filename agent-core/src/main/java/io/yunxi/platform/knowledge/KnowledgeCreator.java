package io.yunxi.platform.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;

/**
 * 知识库创建器 SPI 接口。
 *
 * <p>
 * 每种知识库类型实现此接口，通过 Spring 组件扫描自动发现。
 * 新增知识库类型只需添加新的 {@code @Component} 实现，无需修改已有代码。
 * </p>
 *
 * <h3>V2.0 弃用说明</h3>
 * <p>
 * {@link io.agentscope.core.rag.Knowledge} 在 AgentScope 2.0.0 中标记为
 * {@code @Deprecated(forRemoval=true)}。官方新 RAG 模块计划在后续 minor 版本发布，
 * 届时需要迁移知识库创建逻辑到新的 SPI 接口。
 * </p>
 *
 * <h3>扩展示例</h3>
 *
 * <pre>
 * {@code
 * &#64;Component
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
 * }
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
// TODO: AgentScope 2.0 新 RAG 模块上线后，将 Knowledge 替换为新接口，更新所有 Creator 实现类
@SuppressWarnings("removal")
public interface KnowledgeCreator {

    /**
     * 获取支持的知识库类型标识。
     *
     * <p>
     * 与 {@code agentscope.yml} 中 {@code knowledge-bases.*.type} 对应。
     * 当前支持的类型：bailian、dify、ragflow、simple、haystack
     * </p>
     *
     * @return 类型标识，如 "bailian"、"dify"、"ragflow"、"simple"、"haystack"
     */
    String getType();

    /**
     * 根据配置创建 Knowledge 实例。
     *
     * <p>
     * 所有知识库创建器通过反射加载 AgentScope SDK 类，避免编译期硬依赖。
     * 如果对应的扩展包未安装，将抛出 IllegalArgumentException。
     * </p>
     *
     * @param config 知识库配置（来自 {@code agentscope.yml} 的 {@code knowledge-bases.*} 节点）
     * @return Knowledge 实例
     * @throws IllegalArgumentException 配置参数不合法或扩展包未安装时抛出
     */
    Knowledge create(KnowledgeBaseConfig config);

    /**
     * 默认是否启用（当配置中无 explicit enabled 时的默认行为）。
     *
     * <p>
     * 返回 true 的知识库类型在配置中未显式设置 enabled 时会被自动创建，
     * 返回 false 的类型需要显式设置 enabled=true 才会创建。
     * </p>
     *
     * @return true 表示该类型默认启用
     */
    default boolean isEnabledByDefault() {
        return false;
    }
}
