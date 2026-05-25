package io.yunxi.platform.framework.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 百炼知识库创建器
 *
 * <p>
 * 根据配置创建 {@code BailianKnowledge} 实例，支持阿里云百炼 RAG 服务。
 * 文档管理通过百炼控制台进行。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class BailianKnowledgeCreator implements KnowledgeCreator {

    @Override
    public String getType() {
        return "bailian";
    }

    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        try {
            // 使用反射加载 AgentScope SDK 类，避免编译期硬依赖
            Class<?> bailianConfigClass = Class.forName("io.agentscope.core.rag.impl.BailianConfig");
            Class<?> bailianKnowledgeClass = Class.forName("io.agentscope.core.rag.impl.BailianKnowledge");

            Object bailianConfig = bailianConfigClass.getMethod("builder").invoke(null);
            // BailianConfig.builder().accessKeyId(...).accessKeySecret(...).workspaceId(...).indexId(...).build()
            bailianConfig = bailianConfigClass.getMethod("accessKeyId", String.class)
                    .invoke(bailianConfig, config.getAccessKeyId());
            bailianConfig = bailianConfigClass.getMethod("accessKeySecret", String.class)
                    .invoke(bailianConfig, config.getAccessKeySecret());
            bailianConfig = bailianConfigClass.getMethod("workspaceId", String.class)
                    .invoke(bailianConfig, config.getWorkspaceId());
            bailianConfig = bailianConfigClass.getMethod("indexId", String.class)
                    .invoke(bailianConfig, config.getIndexId());
            Object builtConfig = bailianConfigClass.getMethod("build").invoke(bailianConfig);

            // BailianKnowledge.builder().config(config).build()
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