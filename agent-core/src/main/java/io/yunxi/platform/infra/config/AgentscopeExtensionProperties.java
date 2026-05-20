package io.yunxi.platform.infra.config;

import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AgentScope 扩展功能配置属性类
 * 
 * <p>
 * 配置知识库、记忆存储、MCP服务器等扩展功能。
 * 与 {@link AgentscopeCoreProperties} 配合使用：
 * <ul>
 *   <li>{@link AgentscopeCoreProperties} - 基础配置（API密钥、模型等）</li>
 *   <li>本类 - 扩展功能配置（知识库、记忆、MCP等）</li>
 * </ul>
 * </p>
 *
 * <h3>配置示例</h3>
 * <pre>
 * agentscope:
 *   extensions:
 *     auto-config-enabled: true
 *     knowledge-bases:
 *       product:
 *         enabled: true
 *         type: bailian
 *         access-key-id: ${BAILIAN_ACCESS_KEY_ID}
 *         access-key-secret: ${BAILIAN_ACCESS_KEY_SECRET}
 *         workspace-id: your-workspace-id
 *         index-id: your-index-id
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 3.2.0
 * @see AgentscopeCoreProperties
 * @see AgentscopeAutoConfiguration
 */
@Data
@Component
@ConfigurationProperties(prefix = "agentscope.extensions")
public class AgentscopeExtensionProperties {

    /**
     * 是否启用自动配置
     * <p>
     * 启用后，框架将根据配置自动创建 AgentScope 扩展组件
     * </p>
     */
    private boolean autoConfigEnabled = false;

    /**
     * 知识库配置映射
     * <p>
     * 支持多种知识库类型：bailian、dify、haystack、ragflow、simple
     * </p>
     */
    private Map<String, KnowledgeBaseConfig> knowledgeBases;

    /**
     * 记忆存储配置映射
     * <p>
     * 支持多种记忆存储：mem0、reme、autocontext
     * </p>
     */
    private Map<String, MemoryStoreConfig> memoryStores;

    /**
     * Studio 可视化调试配置
     */
    private StudioConfig studio;

    /**
     * A2A（Agent-to-Agent）协议配置
     */
    private A2AConfig a2a;

    /**
     * Skills 技能配置
     */
    private SkillsConfig skills;

    /**
     * MCP 服务器配置映射
     * <p>
     * 支持多种协议：stdio、http、sse
     * </p>
     */
    private Map<String, McpServerConfig> mcpServers;

    // ==================== 内部配置类定义 ====================

    /**
     * 知识库配置类
     */
    @Data
    public static class KnowledgeBaseConfig {
        /** 是否启用 */
        private boolean enabled = false;
        /** 知识库类型：bailian、dify、haystack、ragflow、simple */
        private String type;

        // 百炼知识库参数
        /** Access Key ID（百炼专用） */
        private String accessKeyId;
        /** Access Key Secret（百炼专用） */
        private String accessKeySecret;
        /** 工作空间 ID（百炼专用） */
        private String workspaceId;
        /** 索引 ID（百炼专用） */
        private String indexId;

        // Dify 知识库参数
        /** API 密钥 */
        private String apiKey;
        /** API 服务地址 */
        private String apiUrl;
        /** 数据集ID（Dify、RAGFlow 使用） */
        private String datasetId;

        // 通用参数
        /** 检索返回的最大文档数 */
        private Integer topK = ConfigDefaults.DEFAULT_TOP_K;
        /** 相似度阈值 */
        private Double scoreThreshold = ConfigDefaults.DEFAULT_SCORE_THRESHOLD;
    }

    /**
     * 记忆存储配置类
     */
    @Data
    public static class MemoryStoreConfig {
        /** 是否启用 */
        private boolean enabled = false;
        /** 存储类型：mem0、reme、autocontext */
        private String type;

        // API 参数
        /** API Key（Mem0 云服务使用） */
        private String apiKey;
        /** API Base URL */
        private String apiBaseUrl;
        /** API URL */
        private String apiUrl;
        /** 超时时间（毫秒） */
        private int timeoutMs = 30000;

        // 用户隔离
        /** 默认用户 ID */
        private String userId;
        /** 启用 ReMe 的用户列表（空表示所有用户都启用） */
        private java.util.List<String> enabledUsers;
    }

    /**
     * Studio 配置类
     */
    @Data
    public static class StudioConfig {
        /** 是否启用 */
        private boolean enabled = false;
        /** Studio 服务地址（必填，无默认值） */
        private String url;
    }

    /**
     * A2A 协议配置类
     */
    @Data
    public static class A2AConfig {
        /** 是否启用 */
        private boolean enabled = false;
        /** 注册中心类型：nacos、consul、static */
        private String registryType = ConfigDefaults.DEFAULT_REGISTRY_TYPE;
        /** 注册中心地址（必填，无默认值） */
        private String registryAddr;
        /** 注册中心命名空间（必填，无默认值） */
        private String namespace;
    }

    /**
     * Skills 配置类
     */
    @Data
    public static class SkillsConfig {
        /** 是否启用 */
        private boolean enabled = false;
    }

    /**
     * MCP 服务器配置类
     */
    @Data
    public static class McpServerConfig {
        /** 是否启用 */
        private boolean enabled = false;
        /** 协议类型：stdio、http、sse */
        private String type;
        /** 命令（stdio 协议使用） */
        private String command;
        /** 命令参数 */
        private java.util.List<String> args;
        /** 服务地址（http/sse 协议使用） */
        private String url;
        /** 超时时间（毫秒） */
        private Integer timeout = ConfigDefaults.DEFAULT_MCP_TIMEOUT_MS;
        /** 请求头 */
        private Map<String, String> headers;
        /** 环境变量 */
        private Map<String, String> env;
    }
}
