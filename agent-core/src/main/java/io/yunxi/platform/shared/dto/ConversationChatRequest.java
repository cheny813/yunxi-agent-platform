package io.yunxi.platform.shared.dto;

import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.config.MemoryParams;
import jakarta.validation.constraints.NotBlank;

/**
 * 会话对话请求 DTO
 *
 * <p>
 * 用于在已有会话中进行对话，支持多轮对话上下文管理。
 * </p>
 *
 * <p>
 * 使用场景：
 * <ul>
 * <li>在已有会话中继续对话</li>
 * <li>控制是否包含历史消息，实现无状态或有状态对话</li>
 * <li>控制历史消息数量，优化性能和成本</li>
 * <li>多轮对话，保持上下文连续性</li>
 * </ul>
 * </p>
 *
 * <p>
 * 与 CreateConversationRequest 的区别：
 * <ul>
 * <li>CreateConversationRequest：创建新会话，初始化对话上下文</li>
 * <li>ConversationChatRequest：在已有会话中进行对话，利用历史上下文</li>
 * </ul>
 * </p>
 *
 * <p>
 * 典型使用流程：
 * <ol>
 * <li>使用 CreateConversationRequest 创建会话，获得 conversationId</li>
 * <li>使用 ConversationChatRequest 调用对话 API，传入 conversationId 和消息</li>
 * <li>系统根据 includeHistory 决定是否包含历史消息</li>
 * <li>如果包含历史，只返回最近的 maxHistory 条消息</li>
 * <li>重复步骤 2-4，实现多轮对话</li>
 * </ol>
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class ConversationChatRequest implements MemoryParams {

    /**
     * 会话 ID
     * <p>
     * 用于标识和追踪多轮对话的上下文。
     * 如果为空，系统会自动创建新会话。
     * </p>
     */
    private String conversationId;

    /**
     * Agent 名称
     * <p>
     * 指定处理此请求的 Agent。
     * 如果会话 ID 存在，则从会话中获取 Agent 名称。
     * </p>
     */
    private String agentName;

    /**
     * 用户消息（必填）
     *
     * <p>
     * 当前用户要发送的消息内容。
     * 支持纯文本、Markdown 格式。
     * </p>
     *
     * <p>
     * 验证规则：不能为空或空白字符
     * </p>
     *
     * <p>
     * 示例：
     * <ul>
     * <li>简单消息："你好，我想创建一个 Java 项目"</li>
     * <li>Markdown："请帮我生成以下代码\n```java\n...\n```"</li>
     * </ul>
     * </p>
     */
    @NotBlank(message = "message 不能为空")
    private String message;

    /**
     * 是否包含历史消息（默认 true）
     *
     * <p>
     * 控制是否将历史对话消息发送给模型，实现有状态的对话。
     * </p>
     *
     * <p>
     * 使用场景：
     * <ul>
     * <li>true（默认）：包含历史消息，实现多轮对话的上下文连续性</li>
     * <li>false：不包含历史消息，每次对话都是独立的，适合无状态场景</li>
     * </ul>
     * </p>
     *
     * <p>
     * 性能影响：
     * <ul>
     * <li>包含历史：消耗更多 token，成本更高，但对话更连贯</li>
     * <li>不包含历史：节省 token，成本更低，但每次需要重新提供上下文</li>
     * </ul>
     * </p>
     */
    private boolean includeHistory = true;

    /**
     * 最大历史消息数（默认 10）
     *
     * <p>
     * 当 includeHistory = true 且 memoryMode = "simple" 时，限制包含的历史消息数量。
     * 只返回最近的 N 条消息，用于控制 token 消耗。
     * </p>
     *
     * <p>
     * 使用场景：
     * <ul>
     * <li>短对话：设置为 5-10，节省 token</li>
     * <li>长对话：设置为 20-50，保持更长上下文</li>
     * <li>默认值：10，平衡性能和上下文</li>
     * </ul>
     * </p>
     *
     * <p>
     * 注意事项：
     * <ul>
     * <li>只在 memoryMode = "simple" 时生效</li>
     * <li>其他模式（smart/full）使用智能记忆系统管理上下文</li>
     * <li>值必须大于 0</li>
     * <li>消息越多，token 消耗越大，响应时间越长</li>
     * </ul>
     * </p>
     */
    private int maxHistory = 10;

    /**
     * 记忆模式（默认 "smart"）
     *
     * <p>
     * 控制对话历史的管理方式，影响上下文构建和 token 消耗。
     * </p>
     *
     * <p>
     * 支持的模式：
     * <ul>
     * <li>"smart"：智能记忆模式（推荐）
     * <ul>
     * <li>使用 AgentScope 的 AutoContextMemory</li>
     * <li>自动压缩对话历史，只保留关键信息</li>
     * <li>支持 6 种渐进式压缩策略</li>
     * <li>双存储机制：工作记忆（压缩）+ 原始记忆（完整）</li>
     * <li>大幅降低 token 消耗，同时保持对话连贯性</li>
     * </ul>
     * </li>
     * <li>"full"：完整记忆模式
     * <ul>
     * <li>保存所有对话历史到 AutoContextMemory</li>
     * <li>每次对话包含完整历史</li>
     * <li>适合需要精确回忆所有细节的场景</li>
     * <li>token 消耗较高</li>
     * </ul>
     * </li>
     * <li>"simple"：简单记忆模式
     * <ul>
     * <li>只保留最近的 N 条消息（maxHistory）</li>
     * <li>不使用记忆压缩</li>
     * <li>适合短对话或测试场景</li>
     * <li>token 消耗最低</li>
     * </ul>
     * </li>
     * <li>"none"：无记忆模式
     * <ul>
     * <li>每次对话都是独立的，不包含历史</li>
     * <li>等同于 includeHistory = false</li>
     * </ul>
     * </li>
     * </ul>
     * </p>
     *
     * <p>
     * 推荐配置：
     * <ul>
     * <li>生产环境："smart" - 平衡性能和上下文</li>
     * <li>精确回忆场景："full" - 完整上下文</li>
     * <li>测试/短对话："simple" - 最小开销</li>
     * </ul>
     * </p>
     */
    private String memoryMode = "full";

    /**
     * 使用历史摘要（默认 true）
     *
     * <p>
     * 当 memoryMode = "smart" 时，是否使用历史摘要功能。
     * </p>
     *
     * <p>
     * 功能说明：
     * <ul>
     * <li>true：对对话历史进行智能摘要，保留关键信息</li>
     * <li>false：不进行摘要，使用原始对话历史</li>
     * </ul>
     * </p>
     */
    private Boolean useHistorySummary = true;

    /**
     * 记忆阈值（默认 30）
     *
     * <p>
     * 当 memoryMode = "smart" 时，触发记忆压缩的阈值。
     * 当对话历史超过此阈值时，开始应用压缩策略。
     * </p>
     *
     * <p>
     * 建议：
     * <ul>
     * <li>短对话：20-30</li>
     * <li>长对话：50-100</li>
     * <li>默认值：30，适合大多数场景</li>
     * </ul>
     * </p>
     */
    private Integer memoryThreshold = 30;

    /**
     * 最后保留消息数（默认 10）
     *
     * <p>
     * 当 memoryMode = "smart" 时，始终保留最近的 N 条消息不压缩。
     * 这些消息会直接出现在上下文中，确保最新的对话不受影响。
     * </p>
     *
     * <p>
     * 建议：
     * <ul>
     * <li>短对话：5-10</li>
     * <li>长对话：15-20</li>
     * <li>默认值：10，平衡上下文和性能</li>
     * </ul>
     * </p>
     */
    private Integer lastKeepMessages = 10;

    /**
     * Token 比例（默认 0.3）
     *
     * <p>
     * 当 memoryMode = "smart" 时，压缩后 token 数占原始 token 数的最大比例。
     * 例如：原始 10000 tokens，比例 0.3，则压缩后最多 3000 tokens。
     * </p>
     *
     * <p>
     * 建议：
     * <ul>
     * <li>激进压缩：0.2-0.3</li>
     * <li>平衡压缩：0.3-0.5</li>
     * <li>保守压缩：0.5-0.7</li>
     * <li>默认值：0.3，平衡压缩率和信息保留</li>
     * </ul>
     * </p>
     */
    private Double tokenRatio = 0.3;

    /**
     * 记忆配置对象（内部使用）
     *
     * <p>
     * 由 SmartMemoryDomainService 使用，不直接对外暴露。
     * </p>
     */
    private MemoryConfig memoryConfig;

    /**
     * 默认构造函数
     *
     * <p>
     * 创建一个空的请求对象。
     * 默认值：includeHistory = true, maxHistory = 10
     * </p>
     */
    public ConversationChatRequest() {
    }

    /**
     * 带参构造函数
     *
     * <p>
     * 快速创建只包含消息的请求对象。
     * 使用默认的历史设置。
     * </p>
     *
     * @param message 用户消息（必填）
     */
    public ConversationChatRequest(String message) {
        this.message = message;
    }

    // ==================== MemoryParams 接口实现 ====================

    /**
     * 获取记忆配置
     *
     * <p>
     * 将现有的记忆参数转换为 MemoryConfig 对象。
     * </p>
     *
     * @return 记忆配置对象
     */
    @Override
    public MemoryConfig getMemoryConfig() {
        if (memoryConfig == null) {
            memoryConfig = new MemoryConfig();
            memoryConfig.setType(memoryMode);
            memoryConfig.setUseHistorySummary(useHistorySummary);
            memoryConfig.setMemoryThreshold(memoryThreshold);
            memoryConfig.setLastKeepMessages(lastKeepMessages);
            memoryConfig.setTokenRatio(tokenRatio);
            memoryConfig.setMaxHistory(maxHistory);
        }
        return memoryConfig;
    }

    /**
     * 设置记忆配置
     *
     * <p>
     * 从 MemoryConfig 对象中提取参数并设置到各个字段。
     * </p>
     *
     * @param config 记忆配置对象
     */
    @Override
    public void setMemoryConfig(MemoryConfig config) {
        if (config != null) {
            this.memoryConfig = config;
            this.memoryMode = config.getType();
            this.useHistorySummary = config.getUseHistorySummary();
            this.memoryThreshold = config.getMemoryThreshold();
            this.lastKeepMessages = config.getLastKeepMessages();
            this.tokenRatio = config.getTokenRatio();
            this.maxHistory = config.getMaxHistory();
        }
    }

    // ==================== Getter 和 Setter 方法 ====================

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isIncludeHistory() {
        return includeHistory;
    }

    public void setIncludeHistory(boolean includeHistory) {
        this.includeHistory = includeHistory;
    }

    public int getMaxHistory() {
        return maxHistory;
    }

    public void setMaxHistory(int maxHistory) {
        this.maxHistory = maxHistory;
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
}
