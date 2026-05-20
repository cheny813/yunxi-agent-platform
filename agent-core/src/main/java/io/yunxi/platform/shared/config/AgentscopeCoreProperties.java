package io.yunxi.platform.shared.config;

import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
}