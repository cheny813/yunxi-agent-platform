package io.yunxi.platform.shared.dto;

import java.util.List;
import java.util.Map;

/**
 * 高级 Agent 运行时配置 DTO
 *
 * <p>
 * 支持 AgentScope Java SDK 的所有高级功能：
 * RAG、MCP、Tool、Memory、Hook 等。
 * </p>
 */
public class AgentRuntimeConfigDto {

    // ==================== RAG 配置 ====================

    /**
     * RAG 模式
     * <ul>
     * <li>GENERIC: 自动检索并注入知识</li>
     * <li>AGENTIC: Agent 主动检索知识</li>
     * <li>NONE: 禁用 RAG</li>
     * </ul>
     */
    private String ragMode = "NONE";

    /**
     * 知识库配置列表
     */
    private List<KnowledgeBaseConfig> knowledgeBases;

    // ==================== MCP 配置 ====================

    /**
     * MCP 服务器配置列表
     */
    private List<McpServerConfig> mcpServers;

    // ==================== Tool 配置 ====================

    /**
     * 工具列表（对象注册方式）
     * 格式：{"className": "io.yunxi.tools.MyTool"}
     */
    private List<String> tools;

    /**
     * 预注册的工具包名称列表
     * 从平台配置的 toolkits 中选择
     */
    private List<String> toolkits;

    // ==================== Memory 配置 ====================

    /**
     * 记忆模式
     * <ul>
     * <li>IN_MEMORY: 仅内存记忆</li>
     * <li>LONG_TERM: 启用长期记忆</li>
     * <li>BOTH: 内存 + 长期记忆</li>
     * </ul>
     */
    private String memoryMode = "IN_MEMORY";

    /**
     * 长期记忆配置
     */
    private LongTermMemoryConfig longTermMemory;

    // ==================== 执行配置 ====================

    /**
     * 最大迭代次数（默认 10）
     */
    private Integer maxIters;

    /**
     * 是否启用元工具（默认 false）
     */
    private Boolean enableMetaTool;

    // ==================== Hook 配置 ====================

    /**
     * 要启用的 Hook 列表
     * 格式：["GenericRAGHook", "SkillHook", "StaticLongTermMemoryHook"]
     */
    private List<String> hooks;

    // ==================== 计划配置 ====================

    /**
     * 是否启用计划记录（默认 false）
     */
    private Boolean enablePlanNotebook;

    /**
     * PlanNotebook 最大子任务数量
     */
    private Integer planMaxSubtasks;

    /**
     * PlanNotebook 自定义提示词
     */
    private String planPrompt;

    /**
     * 是否启用 Plan 可视化监控
     */
    private Boolean enablePlanMonitor;

    // ==================== Skill 配置 ====================

    /**
     * 技能配置列表
     */
    private List<SkillConfig> skills;

    // ==================== 内部类定义 ====================

    /**
     * 知识库配置
     */
    public static class KnowledgeBaseConfig {
        private String name;
        private String type; // "vector", "keyword", "hybrid"
        private Map<String, Object> config;

        public KnowledgeBaseConfig() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Map<String, Object> getConfig() {
            return config;
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }
    }

    /**
     * MCP 服务器配置
     */
    public static class McpServerConfig {
        private String name;
        private String transportType; // "STDIO", "SSE", "HTTP"
        private String command; // STDIO 模式：可执行文件路径
        private List<String> args; // STDIO 模式：命令行参数
        private String url; // SSE/HTTP 模式：服务地址
        private Integer timeout; // 超时时间（秒）

        public McpServerConfig() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTransportType() {
            return transportType;
        }

        public void setTransportType(String transportType) {
            this.transportType = transportType;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Integer getTimeout() {
            return timeout;
        }

        public void setTimeout(Integer timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * 长期记忆配置
     */
    public static class LongTermMemoryConfig {
        private String type; // "VECTOR", "SQLITE", etc.
        private Map<String, Object> config;
        private String retrievalMode; // "ALL", "ONLY_LT", "NONE"

        public LongTermMemoryConfig() {
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Map<String, Object> getConfig() {
            return config;
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }

        public String getRetrievalMode() {
            return retrievalMode;
        }

        public void setRetrievalMode(String retrievalMode) {
            this.retrievalMode = retrievalMode;
        }
    }

    /**
     * 技能配置
     */
    public static class SkillConfig {
        private String name;
        private String description;
        private Map<String, Object> config;

        public SkillConfig() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Map<String, Object> getConfig() {
            return config;
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }
    }

    // ==================== Getters & Setters ====================

    public String getRagMode() {
        return ragMode;
    }

    public void setRagMode(String ragMode) {
        this.ragMode = ragMode;
    }

    public List<KnowledgeBaseConfig> getKnowledgeBases() {
        return knowledgeBases;
    }

    public void setKnowledgeBases(List<KnowledgeBaseConfig> knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
    }

    public List<McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(List<McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }

    public List<String> getToolkits() {
        return toolkits;
    }

    public void setToolkits(List<String> toolkits) {
        this.toolkits = toolkits;
    }

    public String getMemoryMode() {
        return memoryMode;
    }

    public void setMemoryMode(String memoryMode) {
        this.memoryMode = memoryMode;
    }

    public LongTermMemoryConfig getLongTermMemory() {
        return longTermMemory;
    }

    public void setLongTermMemory(LongTermMemoryConfig longTermMemory) {
        this.longTermMemory = longTermMemory;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public Boolean getEnableMetaTool() {
        return enableMetaTool;
    }

    public void setEnableMetaTool(Boolean enableMetaTool) {
        this.enableMetaTool = enableMetaTool;
    }

    public List<String> getHooks() {
        return hooks;
    }

    public void setHooks(List<String> hooks) {
        this.hooks = hooks;
    }

    public Boolean getEnablePlanNotebook() {
        return enablePlanNotebook;
    }

    public void setEnablePlanNotebook(Boolean enablePlanNotebook) {
        this.enablePlanNotebook = enablePlanNotebook;
    }

    public Integer getPlanMaxSubtasks() {
        return planMaxSubtasks;
    }

    public void setPlanMaxSubtasks(Integer planMaxSubtasks) {
        this.planMaxSubtasks = planMaxSubtasks;
    }

    public String getPlanPrompt() {
        return planPrompt;
    }

    public void setPlanPrompt(String planPrompt) {
        this.planPrompt = planPrompt;
    }

    public Boolean getEnablePlanMonitor() {
        return enablePlanMonitor;
    }

    public void setEnablePlanMonitor(Boolean enablePlanMonitor) {
        this.enablePlanMonitor = enablePlanMonitor;
    }

    public List<SkillConfig> getSkills() {
        return skills;
    }

    public void setSkills(List<SkillConfig> skills) {
        this.skills = skills;
    }
}
