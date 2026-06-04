package io.yunxi.platform.shared.config;

import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/**
 * AgentScope 核心配置属性类
 *
 * <p>
 * 核心业务配置入口，配置前缀为 agentscope.core 以避免与WebSocket配置冲突
 * </p>
 *
 * <h3>配置示例 (application.yml)</h3>
 *
 * <pre>
 * agentscope.core:
 *   api-key: ${DASHSCOPE_API_KEY}
 *   model-name: qwen-plus
 *   provider: dashscope
 *   chat-timeout-seconds: 60
 *   studio:
 *     enabled: true
 *     url: http://localhost:3000
 * </pre>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "agentscope.core")
public class AgentscopeCoreProperties {

    /**
     * API 密钥（必填）
     */
    private String apiKey;

    /**
     * 默认模型名称
     */
    private String modelName = ConfigDefaults.DEFAULT_MODEL_NAME;

    /**
     * 模型提供商
     * 可选值：dashscope, openai, baidu, huawei
     */
    private String provider = ConfigDefaults.DEFAULT_PROVIDER;

    /**
     * 默认系统提示词
     */
    private String defaultPrompt = ConfigDefaults.DEFAULT_SYSTEM_PROMPT;

    /**
     * 对话超时秒数
     */
    private Integer chatTimeoutSeconds = ConfigDefaults.DEFAULT_CHAT_TIMEOUT_SECONDS;

    /**
     * Studio 可视化调试配置
     */
    private StudioConfig studio = new StudioConfig();

    /**
     * MCP 服务器配置映射
     */
    private Map<String, McpServerConfig> mcpServers;

    /**
     * Agent 工作区基础路径
     */
    private String workspaceBasePath = "./.agentscope/workspace";

    /**
     * 获取 Agent 工作区基础路径（始终返回绝对路径）
     * <p>
     * 重写 Lombok {@code @Data} 生成的 getter，确保路径为绝对路径，
     * 避免在 Windows 上与 {@code filesystem.glob()} 返回的绝对路径
     * 做 {@code Path.relativize()} 时因路径类型不一致而抛出异常。
     * </p>
     */
    public String getWorkspaceBasePath() {
        if (workspaceBasePath == null || workspaceBasePath.isBlank()) {
            return "./.agentscope/workspace";
        }
        // 转换为绝对路径字符串
        return Path.of(workspaceBasePath).toAbsolutePath().normalize().toString();
    }

    /**
     * Compaction（消息压缩）配置
     */
    private CompactionProperties compaction = new CompactionProperties();

    /** Session 持久化配置 */
    private SessionProperties session = new SessionProperties();

    /** 全局默认生成参数 */
    private GenerationConfig generation = new GenerationConfig();

    /** Shell 命令安全配置 */
    private ShellConfig shell = new ShellConfig();

    /**
     * 各 Provider 配置
     */
    private ProviderConfig dashscope = new ProviderConfig();
    private ProviderConfig openai = new ProviderConfig();
    private ProviderConfig baidu = new ProviderConfig();
    private ProviderConfig huawei = new ProviderConfig();

    // 显式提供 getter 方法，确保兼容性
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Integer getChatTimeoutSeconds() {
        return chatTimeoutSeconds;
    }

    public void setChatTimeoutSeconds(Integer chatTimeoutSeconds) {
        this.chatTimeoutSeconds = chatTimeoutSeconds;
    }

    public StudioConfig getStudio() {
        return studio;
    }

    public void setStudio(StudioConfig studio) {
        this.studio = studio;
    }

    /**
     * Provider 配置类
     *
     * @author yunxi-agent-platform
     */
    @Data
    public static class ProviderConfig {
        /**
         * API Key
         */
        private String apiKey;

        /**
         * 模型名称
         */
        private String model;

        /**
         * API 基础地址（可选，用于兼容 OpenAI API 的服务）
         */
        private String baseUrl;

        /**
         * 超时时间（毫秒）
         */
        private Integer timeout = ConfigDefaults.DEFAULT_API_TIMEOUT_MS;
    }

    /**
     * Studio 配置类
     *
     * <p>
     * 注意：project 和 url 没有默认值，必须在配置文件中显式设置。
     * </p>
     *
     * @author yunxi-agent-platform
     */
    @Data
    public static class StudioConfig {
        /**
         * 是否启用 Studio
         */
        private Boolean enabled = false;

        /**
         * Studio 服务地址（必填，无默认值）
         */
        private String url;

        /**
         * 项目名称（必填，无默认值）
         */
        private String project;

        /**
         * 运行名称前缀
         */
        private String runNamePrefix = "run_";
    }

    /**
     * MCP 服务器配置类
     *
     * @author yunxi-agent-platform
     */
    @Data
    public static class McpServerConfig {
        /** 是否启用 */
        private boolean enabled = false;
        /** 服务器类型（sse 或 stdio） */
        private String type;
        /** 启动命令（stdio模式） */
        private String command;
        /** 命令参数列表（stdio模式） */
        private java.util.List<String> args;
        /** 服务器地址（sse模式） */
        private String url;
        /** 连接超时时间（毫秒） */
        private Integer timeout = ConfigDefaults.DEFAULT_MCP_TIMEOUT_MS;
        /** 自定义请求头 */
        private Map<String, String> headers;
        /** 环境变量 */
        private Map<String, String> env;
    }

    /**
     * Compaction（消息压缩）配置类
     *
     * <p>
     * 控制 HarnessAgent 的消息压缩策略，当消息数量或 token 数超过阈值时自动触发压缩。
     * </p>
     *
     * @author yunxi-agent-platform
     */
    @Data
    public static class CompactionProperties {
        /** 触发压缩的消息数量阈值 */
        private int triggerMessages = 50;
        /** 触发压缩的 token 数量阈值 */
        private int triggerTokens = 80000;
        /** 压缩后保留的消息数量 */
        private int keepMessages = 20;
        /** 压缩前是否刷新记忆 */
        private boolean flushBeforeCompact = true;
        /** 压缩前是否卸载记忆 */
        private boolean offloadBeforeCompact = true;
    }

    /**
     * Session 持久化配置
     * <p>
     * 控制 Agent 运行时状态的持久化后端。
     * 默认使用 {@code workspace}（文件系统），无需额外依赖。
     * 跨实例共享需配置 {@code redis}（需 spring-boot-starter-data-redis）。
     * </p>
     */
    @Data
    public static class SessionProperties {
        /** Session 后端类型：workspace（默认）/ redis */
        private String type = "workspace";
    }

    /**
     * 全局默认生成参数
     * <p>
     * 对应框架 {@link io.agentscope.core.model.GenerateOptions}，
     * 在 Agent 定义 YAML 中未指定时使用此默认值。
     * 配置前缀：agentscope.core.generation
     * </p>
     *
     * <pre>
     * agentscope.core.generation:
     *   temperature: 0.7
     *   max-tokens: 4096
     *   top-p: 0.9
     *   cache-control: false
     * </pre>
     */
    @Data
    public static class GenerationConfig {
        /** 生成温度（默认 0.7） */
        private Double temperature = 0.7;

        /** 最大输出 token 数（默认 4096） */
        private Integer maxTokens = 4096;

        /** top_p 采样参数（默认 0.9） */
        private Double topP = 0.9;

        /**
         * 是否启用 Prompt Caching（默认 false）
         * <p>
         * 开启后，框架自动给 system message 和最后一条消息
         * 添加 cache_control: {"type": "ephemeral"}。
         * 各提供商支持：
         * <ul>
         *   <li>OpenAI：自动前缀匹配（>1024 token）</li>
         *   <li>Anthropic：显式 cache_control 标记</li>
         *   <li>DashScope（Qwen）：自动前缀缓存</li>
         * </ul>
         * </p>
         */
        private Boolean cacheControl = false;
    }

    /**
     * Shell 命令安全配置
     * <p>
     * 对应框架 {@link io.agentscope.core.tool.coding.ShellCommandTool} 的安全控制参数。
     * 配置前缀：agentscope.core.shell
     * </p>
     *
     * <pre>
     * agentscope.core.shell:
     *   allowed-commands: [ls, cat, grep, python, node]
     *   approval-enabled: false
     *   base-dir: /data/workspace
     * </pre>
     */
    @Data
    public static class ShellConfig {
        /** 允许自动执行的命令列表（空集合或 null 表示所有命令需审批） */
        private java.util.Set<String> allowedCommands = java.util.Set.of("ls", "cat", "grep", "wc", "echo");

        /** 是否启用人工审批回调（false 时白名单外命令直接拒绝） */
        private boolean approvalEnabled = false;

        /** 工作目录限制（null 表示不限制） */
        private String baseDir;
    }
}
