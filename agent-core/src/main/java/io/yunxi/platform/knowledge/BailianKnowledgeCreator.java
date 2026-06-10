package io.yunxi.platform.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 百炼知识库创建器。
 *
 * <p>
 * 根据配置创建 {@code BailianKnowledge} 实例，支持阿里云百炼 RAG 服务。
 * 文档管理通过百炼控制台进行，此创建器仅负责配置连接参数。
 * </p>
 *
 * <p>
 * 必要配置：
 * - accessKeyId：阿里云 AccessKey ID
 * - accessKeySecret：阿里云 AccessKey Secret
 * - workspaceId：百炼工作空间 ID
 * - indexId：百炼索引 ID
 * </p>
 *
 * <p>
 * TODO: AgentScope 2.0 新 RAG 模块上线后迁移到新 API。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@SuppressWarnings("removal")
public class BailianKnowledgeCreator implements KnowledgeCreator {

        /**
         * 获取知识库类型标识。
         *
         * @return "bailian"
         */
        @Override
        public String getType() {
                return "bailian";
        }

        /**
         * 创建 BailianKnowledge 实例。
         *
         * <p>
         * 创建流程：
         * 1. 通过反射加载 BailianConfig 和 BailianKnowledge 类
         * 2. 构建 BailianConfig（accessKeyId、accessKeySecret、workspaceId、indexId）
         * 3. 构建 BailianKnowledge 实例
         * </p>
         *
         * @param config 知识库配置
         * @return BailianKnowledge 实例
         * @throws IllegalArgumentException 配置参数不合法时抛出
         */
        @Override
        public Knowledge create(KnowledgeBaseConfig config) {
                try {
                        // 反射加载 SDK 类，避免编译期硬依赖
                        Class<?> bailianConfigClass = Class.forName("io.agentscope.core.rag.impl.BailianConfig");
                        Class<?> bailianKnowledgeClass = Class.forName("io.agentscope.core.rag.impl.BailianKnowledge");

                        // 构建 BailianConfig
                        Object bailianConfig = bailianConfigClass.getMethod("builder").invoke(null);
                        bailianConfig = bailianConfigClass.getMethod("accessKeyId", String.class)
                                        .invoke(bailianConfig, config.getAccessKeyId());
                        bailianConfig = bailianConfigClass.getMethod("accessKeySecret", String.class)
                                        .invoke(bailianConfig, config.getAccessKeySecret());
                        bailianConfig = bailianConfigClass.getMethod("workspaceId", String.class)
                                        .invoke(bailianConfig, config.getWorkspaceId());
                        bailianConfig = bailianConfigClass.getMethod("indexId", String.class)
                                        .invoke(bailianConfig, config.getIndexId());
                        Object builtConfig = bailianConfigClass.getMethod("build").invoke(bailianConfig);

                        // 构建 BailianKnowledge
                        Object builder = bailianKnowledgeClass.getMethod("builder").invoke(null);
                        builder = bailianKnowledgeClass.getMethod("config", bailianConfigClass)
                                        .invoke(builder, builtConfig);
                        Knowledge knowledge = (Knowledge) bailianKnowledgeClass.getMethod("build").invoke(builder);

                        log.debug("已创建 BailianKnowledge: workspaceId={}, indexId={}",
                                        config.getWorkspaceId(), config.getIndexId());
                        return knowledge;

                } catch (Exception e) {
                        log.error("创建 BailianKnowledge 失败: {}", e.getMessage());
                        throw new IllegalArgumentException("创建 BailianKnowledge 失败: " + e.getMessage(), e);
                }
        }
}
