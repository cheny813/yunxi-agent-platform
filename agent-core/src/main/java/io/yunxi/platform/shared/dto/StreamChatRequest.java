package io.yunxi.platform.shared.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.config.MemoryParams;

/**
 * 流式对话请求 DTO
 *
 * <p>
 * 用于发起流式输出的对话请求，支持 SSE（Server-Sent Events）实时推送响应。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class StreamChatRequest implements MemoryParams {

    /**
     * 用户消息
     */
    @NotBlank(message = "message 不能为空")
    private String message;

    /**
     * 是否输出思考过程（默认 false）
     */
    private boolean enableThinking = false;

    /**
     * 输出块大小（字符数，默认 50）
     */
    private int chunkSize = 50;

    /**
     * 记忆模式（默认 "smart"）
     *
     * <p>
     * 仅在基于会话的流式对话中生效（chatStreamWithConversation）。
     * 控制对话历史的管理方式，影响上下文构建和 token 消耗。
     * </p>
     *
     * <p>
     * 支持的模式：
     * <ul>
     * <li>"smart"：智能记忆模式（推荐）</li>
     * <li>"full"：完整记忆模式</li>
     * <li>"short_term"：短期记忆模式（Redis 滑动窗口）</li>
     * <li>"none"：无记忆模式</li>
     * </ul>
     * </p>
     */
    private String memoryMode = "full";

    /**
     * 使用历史摘要（默认 true）
     */
    private Boolean useHistorySummary = true;

    /**
     * 记忆阈值（默认 30）
     */
    private Integer memoryThreshold = 30;

    /**
     * 最后保留消息数（默认 10）
     */
    private Integer lastKeepMessages = 10;

    /**
     * Token 比例（默认 0.3）
     */
    private Double tokenRatio = 0.3;

    /**
     * 上下文数据（前端自动收集的当前页面业务数据）
     *
     * <p>
     * 用于实现 Context Injection 功能。
     * 前端在发送消息时自动收集当前页面的关键数据（如当前查看的食谱、营养标准等），
     * AI 可以根据这些上下文数据提供更精准的回答。
     * </p>
     *
     * <p>
     * 数据格式示例：
     *
     * <pre>
     * {
     *   "pageType": "recipe-detail",
     *   "recipeId": 12345,
     *   "recipeName": "营养均衡周食谱",
     *   "nutritionData": {
     *     "calories": 1800,
     *     "protein": 70,
     *     "fat": 60,
     *     "carbs": 200
     *   },
     *   "targetGroup": "小学生",
     *   "mealType": "午餐"
     * }
     * </pre>
     * </p>
     */
    private Map<String, Object> contextData;

    /**
     * 是否使用 A2A (Agent-to-Agent) 模式（默认 false）
     *
     * <p>
     * <b>true</b>：启用 A2A 模式，Supervisor Agent 将协调多个专家 Agent 完成任务
     * <br>
     * <b>false</b>：不使用 A2A 模式，直接调用单个 Agent（响应更快）
     * </p>
     * <p>
     * <b>说明</b>：
     * A2A 模式适用于复杂任务，通过多个专家 Agent 协作完成，但响应时间较长。
     * 简单任务建议关闭 A2A 模式以获得更快的响应。
     * </p>
     */
    private Boolean useA2A = false;

    /**
     * 响应深度模式（默认 "standard"）
     *
     * <p>
     * 控制对话流程的开销级别，影响 RAG、记忆、场景检测等环节：
     * </p>
     * <ul>
     * <li><b>quick</b>：快速模式 — 跳过 RAG、记忆、场景检测，直接调用 Agent，响应最快</li>
     * <li><b>standard</b>：标准模式 — 完整流程（记忆 + RAG + 场景检测），适合大多数场景</li>
     * <li><b>deep</b>：深度模式 — 标准流程 + A2A 心跳 + 更长超时，适合复杂多步任务</li>
     * </ul>
     *
     * <p>
     * 设置 responseMode=deep 会自动启用 useA2A 的 SSE 心跳效果。
     * </p>
     */
    private String responseMode = "standard";

    /**
     * Profile 名称（可选）
     * <p>
     * 指定使用 Agent 的哪个 Profile。
     * 不同 Profile 可有不同的工具集、提示词和运行参数。
     * 未指定时使用默认 Agent（向后兼容）。
     * </p>
     */
    private String profile;

    /**
     * A2A 模式下的超时时间（秒，默认 180）
     *
     * <p>
     * 仅在 useA2A=true 时生效。
     * 由于 A2A 模式涉及多个 Agent 协作，需要更长的超时时间。
     * </p>
     */
    private Integer a2aTimeout = 180;

    /**
     * 记忆配置对象（内部使用）
     */
    private MemoryConfig memoryConfig;

    /**
     * 默认构造函数
     */
    public StreamChatRequest() {
    }

    /**
     * 带参构造函数
     *
     * @param message 用户消息
     */
    public StreamChatRequest(String message) {
        this.message = message;
    }

    // ==================== MemoryParams 接口实现 ====================

    /**
     * 获取记忆配置
     *
     * @return 记忆配置对象
     */
    @Override
    public MemoryConfig getMemoryConfig() {
        if (memoryConfig == null) {
            memoryConfig = new MemoryConfig();
            memoryConfig.setMemoryMode(memoryMode);
            memoryConfig.setUseHistorySummary(useHistorySummary);
            memoryConfig.setMemoryThreshold(memoryThreshold);
            memoryConfig.setLastKeepMessages(lastKeepMessages);
            memoryConfig.setTokenRatio(tokenRatio);
            memoryConfig.setMaxHistory(10); // 流式对话默认使用 10
        }
        return memoryConfig;
    }

    /**
     * 设置记忆配置
     *
     * @param config 记忆配置对象
     */
    @Override
    public void setMemoryConfig(MemoryConfig config) {
        if (config != null) {
            this.memoryConfig = config;
            this.memoryMode = config.getMemoryMode();
            this.useHistorySummary = config.getUseHistorySummary();
            this.memoryThreshold = config.getMemoryThreshold();
            this.lastKeepMessages = config.getLastKeepMessages();
            this.tokenRatio = config.getTokenRatio();
        }
    }

    // ==================== Getter 和 Setter 方法 ====================

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public String getMemoryMode() {
        return memoryMode;
    }

    public void setMemoryMode(String memoryMode) {
        this.memoryMode = memoryMode;
    }

    public Boolean getUseHistorySummary() {
        return useHistorySummary;
    }

    public void setUseHistorySummary(Boolean useHistorySummary) {
        this.useHistorySummary = useHistorySummary;
    }

    public Integer getMemoryThreshold() {
        return memoryThreshold;
    }

    public void setMemoryThreshold(Integer memoryThreshold) {
        this.memoryThreshold = memoryThreshold;
    }

    public Integer getLastKeepMessages() {
        return lastKeepMessages;
    }

    public void setLastKeepMessages(Integer lastKeepMessages) {
        this.lastKeepMessages = lastKeepMessages;
    }

    public Double getTokenRatio() {
        return tokenRatio;
    }

    public void setTokenRatio(Double tokenRatio) {
        this.tokenRatio = tokenRatio;
    }

    public Map<String, Object> getContextData() {
        return contextData;
    }

    public void setContextData(Map<String, Object> contextData) {
        this.contextData = contextData;
    }

    public boolean isUseA2A() {
        return useA2A;
    }

    public void setUseA2A(boolean useA2A) {
        this.useA2A = useA2A;
    }

    public int getA2aTimeout() {
        return a2aTimeout;
    }

    public void setA2aTimeout(int a2aTimeout) {
        this.a2aTimeout = a2aTimeout;
    }

    public String getResponseMode() {
        return responseMode;
    }

    public void setResponseMode(String responseMode) {
        this.responseMode = responseMode;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    /**
     * 是否为快速模式
     */
    public boolean isQuickMode() {
        return "quick".equalsIgnoreCase(responseMode);
    }

    /**
     * 是否为深度模式
     */
    public boolean isDeepMode() {
        return "deep".equalsIgnoreCase(responseMode);
    }
}
