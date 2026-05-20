package io.yunxi.platform.shared.dto;

import lombok.Data;
import java.util.Map;
import java.util.List;

/**
 * 高级 Agent 配置数据传输对象（DTO）
 *
 * <p>
 * 本类封装了 AgentScope 框架支持的所有高级功能配置，
 * 用于在服务层和控制器之间传递完整的 Agent 配置信息。
 * 相比基础的 AgentDefinition，本类提供了更细粒度的功能控制。
 * </p>
 *
 * <h3>功能模块</h3>
 * <ul>
 * <li><b>模型配置 (ModelConfig)</b>：定义 LLM 模型的连接参数和生成参数</li>
 * <li><b>RAG 配置 (RagConfig)</b>：定义知识库检索增强生成的参数</li>
 * <li><b>记忆配置 (MemoryConfig)</b>：定义 Agent 的上下文记忆策略</li>
 * <li><b>工具配置 (ToolkitConfig)</b>：定义 Agent 可用的工具集</li>
 * <li><b>技能配置 (SkillConfig)</b>：定义 Agent 可调用的专业技能</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <ol>
 * <li>从 YAML 配置文件反序列化 Agent 定义</li>
 * <li>通过 API 动态创建或更新 Agent</li>
 * <li>在服务层组装 Agent 的完整运行时配置</li>
 * </ol>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @see io.yunxi.platform.config.AgentDefinition 基础 Agent 定义
 */
@Data
public class AdvancedAgentConfigDto {

    // ==================== 基础配置 ====================

    /** Agent 唯一标识名称 */
    private String name;

    /** 系统提示词，定义 Agent 的角色和行为规范 */
    private String sysPrompt;

    // ==================== 模块配置 ====================

    /** 模型配置：定义 LLM 连接和生成参数 */
    private ModelConfigDto modelConfig;

    /** RAG 配置：定义知识库检索参数 */
    private RagConfig ragConfig;

    /** 记忆配置：定义上下文记忆策略 */
    private MemoryConfig memoryConfig;

    /** 工具配置：定义可用工具列表 */
    private ToolkitConfig toolkitConfig;

    /** 技能配置：定义可调用技能列表 */
    private SkillConfig skillConfig;

    // ==================== 功能开关 ====================

    /** 是否启用结构化输出（强制模型输出符合 Schema 的 JSON） */
    private Boolean structuredOutput;

    /** 是否启用 AGUI（Agent GUI 交互界面） */
    private Boolean aguiEnabled;

    /** 是否启用 Hook（Agent 生命周期钩子） */
    private Boolean hookEnabled;

    // ==================== 执行配置 ====================

    /** 最大迭代次数（限制 Agent 的推理-行动循环次数） */
    private Integer maxIters;

    /** 是否启用元工具（让 Agent 能够创建和使用新工具） */
    private Boolean enableMetaTool;

    // ==================== MCP 配置 ====================

    /** MCP 服务器列表：定义 Agent 可访问的外部服务 */
    private List<String> mcpServers;

    // ==================== 扩展配置 ====================

    /** 其他高级配置项（用于扩展功能） */
    private Map<String, Object> advancedOptions;

    /**
     * 模型配置类（DTO）
     *
     * <p>
     * 定义大语言模型的连接参数和生成参数，支持多种模型提供商。
     * </p>
     */
    @Data
    public static class ModelConfigDto {
        /** 模型提供商：dashscope、openai、claude、baidu、huawei */
        private String provider;
        /** API 密钥（可选，未设置时使用全局配置） */
        private String apiKey;
        /** 模型名称，如 qwen-plus、gpt-4、claude-3-opus */
        private String modelName;
        /** 华为云项目 ID（仅华为云模型需要） */
        private String projectId;
        /** API 基础 URL（用于自定义部署） */
        private String baseUrl;
        /** 温度参数（0.0-2.0），控制输出的随机性 */
        private Double temperature;
        /** 最大生成 Token 数 */
        private Integer maxTokens;
        /** 是否启用流式输出 */
        private Boolean stream;

        public String getProvider() {
            return provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public String getProjectId() {
            return projectId;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public Double getTemperature() {
            return temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public Boolean getStream() {
            return stream;
        }
    }

    /**
     * RAG（检索增强生成）配置类
     *
     * <p>
     * 定义知识库检索的参数，用于从外部知识库检索相关文档增强 Agent 的回答。
     * </p>
     */
    @Data
    public static class RagConfig {
        /** 知识库类型：bailian、dify、haystack、ragflow */
        private String type;
        /** API 密钥 */
        private String apiKey;
        /** 集合名称（百炼、Haystack 使用） */
        private String collectionName;
        /** 数据集 ID（Dify、RAGFlow 使用） */
        private String datasetId;
        /** API 服务地址 */
        private String apiUrl;
        /** 流程 ID（RAGFlow 使用） */
        private String flowId;
        /** 检索返回的最大文档数 */
        private Integer retrieveLimit;
        /** 相似度阈值（0.0-1.0） */
        private Double retrieveScoreThreshold;
        /** 知识库 ID 列表 */
        private List<String> knowledgeBases;
    }

    /**
     * 记忆配置类
     *
     * <p>
     * 定义 Agent 的上下文记忆策略，支持多种记忆存储后端。
     * </p>
     */
    @Data
    public static class MemoryConfig {
        /** 记忆类型：in-memory（内存）、autocontext（自动上下文）、reme、mem0 */
        private String type;
        /** 最大 Token 数（限制上下文窗口大小） */
        private Integer maxTokens;
        /** 扩展配置参数 */
        private Map<String, Object> config;
    }

    /**
     * 工具配置类
     *
     * <p>
     * 定义 Agent 可使用的工具列表和工具配置。
     * </p>
     */
    @Data
    public static class ToolkitConfig {
        /** 启用的工具类全限定名列表 */
        private List<String> enabledTools;
        /** 工具特定配置 */
        private Map<String, Object> toolConfigs;
    }

    /**
     * 技能配置类
     *
     * <p>
     * 定义 Agent 可调用的专业技能列表。
     * </p>
     */
    @Data
    public static class SkillConfig {
        /** 启用的技能名称列表 */
        private List<String> enabledSkills;
        /** 技能特定配置 */
        private Map<String, Object> skillConfigs;
        /**
         * Supervisor 模式：专家 Agent 名称列表
         * <p>
         * 配置后，当前 Agent 将作为 Supervisor，协调这些专家 Agent 完成任务。
         * 例如：["dish-searcher", "nutrition-evaluator", "recipe-composer"]
         * </p>
         */
        private List<String> experts;
    }

    // ==================== Getter 方法 ====================

    public String getName() {
        return name;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public ModelConfigDto getModelConfig() {
        return modelConfig;
    }

    public RagConfig getRagConfig() {
        return ragConfig;
    }

    public MemoryConfig getMemoryConfig() {
        return memoryConfig;
    }

    public ToolkitConfig getToolkitConfig() {
        return toolkitConfig;
    }

    public SkillConfig getSkillConfig() {
        return skillConfig;
    }
}
