package io.yunxi.platform.shared.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 统一对话请求 DTO
 *
 * <p>
 * 这是框架的核心简化特性，一个 API 支持多种对话模式：
 * <ul>
 * <li>普通对话 / 流式对话 / 结构化输出 - 通过 mode 参数控制</li>
 * <li>单轮对话 / 多轮会话 - 通过 conversationId 或自动管理</li>
 * <li>同步响应 / 异步任务 - 通过 async 参数控制</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Data
public class UnifiedChatRequest {

    // ==================== 基础参数（必填） ====================

    /**
     * 用户消息（必填）
     * 支持纯文本或包含特殊格式的消息
     */
    @NotBlank(message = "message 不能为空")
    private String message;

    // ==================== Agent 参数（可选） ====================

    /**
     * Agent 名称（可选）
     * <p>
     * 默认使用配置文件中的 default-agent 或第一个启用的 Agent
     * </p>
     */
    private String agentName;

    // ==================== 会话参数（可选） ====================

    /**
     * 会话 ID（可选）
     * <p>
     * 如果提供，在指定会话中进行对话，自动包含历史消息。
     * 如果不提供，根据 autoManageConversation 参数决定是否自动创建会话。
     * </p>
     */
    private String conversationId;

    /**
     * 用户 ID（可选，用于自动会话管理）
     * <p>
     * 当 autoManageConversation=true 时，系统根据 userId 为用户管理会话生命周期。
     * </p>
     */
    private String userId;

    /**
     * 是否自动管理会话（默认 true）
     * <p>
     * <b>true</b>：如果没有提供 conversationId，系统自动为用户创建或复用会话
     * <br>
     * <b>false</b>：不自动管理，每次都是独立的单轮对话
     * </p>
     */
    private Boolean autoManageConversation = true;

    /**
     * 记忆模式（默认 smart）
     * <p>
     * <b>full</b>：完整记忆模式
     * - 使用全部历史消息
     * - 适合重要对话、复杂推理
     * </p>
     * <p>
     * <b>smart</b>：智能记忆模式（推荐，模拟人类记忆）
     * - 使用 AgentScope AutoContextMemory
     * - 自动压缩历史：保留最近 N 条消息，其他消息智能摘要
     * - 双存储机制：工作存储（摘要）+ 原始存储（完整）
     * - 类似人类记忆：记住关键信息，按需回忆细节
     * </p>
     * <p>
     * <b>simple</b>：简单记忆模式
     * - 只使用最近 N 条完整消息
     * - 传统方式，不使用自动摘要
     * </p>
     */
    private String memoryMode = "full";

    /**
     * 是否使用历史摘要（默认 true）
     * <p>
     * 在 smart 模式下，是否使用 LLM 生成历史摘要。
     * 设置为 false 时，只保留最近 N 条完整消息，不生成摘要。
     * </p>
     */
    private Boolean useHistorySummary = true;

    /**
     * 检索关键词列表（可选）
     * <p>
     * 在 smart 模式下，用于从完整历史中检索相关消息。
     * AgentScope AutoContextMemory 会根据这些关键词从原始存储中检索相关历史。
     * </p>
     */
    private List<String> retrieveKeywords;

    /**
     * 最大历史消息数（默认 30）
     * <p>
     * 在 smart 模式下，这是 AutoContextMemory 的 msgThreshold 参数。
     * 超过此数量的消息会触发自动压缩。
     * </p>
     */
    private Integer memoryThreshold = 30;

    /**
     * 保留的最近消息数（默认 10）
     * <p>
     * 在 smart 模式下，这是 AutoContextMemory 的 lastKeep 参数。
     * 最近的 N 条消息不会被压缩，保持完整内容。
     * </p>
     */
    private Integer lastKeepMessages = 10;

    /**
     * Token 压缩比例（默认 0.3）
     * <p>
     * 在 smart 模式下，这是 AutoContextMemory 的 tokenRatio 参数。
     * 压缩目标是使历史消息的 token 数降至此比例。
     * </p>
     */
    private Double tokenRatio = 0.3;

    // ==================== 响应模式（可选） ====================

    /**
     * 响应模式（默认 stream）
     * <p>
     * <b>stream</b>：流式输出，实时返回生成的内容（SSE 格式）
     * <br>
     * <b>sync</b>：同步输出，等待完整回复后一次性返回
     * </p>
     * <p>
     * <b>注意</b>：此参数仅控制响应方式，不控制输出格式。
     * 若需要结构化输出，请设置 {@code structured=true}
     * </p>
     */
    @Pattern(regexp = "stream|sync", message = "mode 必须是 stream 或 sync")
    private String mode = "stream";

    /**
     * 是否结构化输出（默认 false）
     * <p>
     * <b>true</b>：返回结构化数据（JSON 格式），需配合 Agent 配置的 schema 或请求中的 schema 参数
     * <br>
     * <b>false</b>：返回普通文本
     * </p>
     * <p>
     * 支持与 mode 参数组合：
     * <ul>
     * <li>mode=stream + structured=true：流式结构化输出</li>
     * <li>mode=sync + structured=true：同步结构化输出</li>
     * <li>mode=stream + structured=false：流式文本输出（默认）</li>
     * <li>mode=sync + structured=false：同步文本输出</li>
     * </ul>
     * </p>
     */
    private Boolean structured = false;

    /**
     * 流式输出的事件过滤列表（可选）
     * <p>
     * 仅在 mode=stream 时生效。用于控制前端接收哪些类型的事件。
     * </p>
     * <p>
     * 支持的事件类型：
     * <ul>
     * <li><b>thinking</b>：推理过程（REASONING 事件）</li>
     * <li><b>content</b>：工具调用结果（TOOL_RESULT 事件）</li>
     * <li><b>hint</b>：提示信息（HINT 事件，来自 RAG、memory、planning）</li>
     * <li><b>summary</b>：摘要信息（SUMMARY 事件，最大迭代次数到达时）</li>
     * <li><b>structured</b>：最终结构化数据（必须包含，无法过滤）</li>
     * </ul>
     * </p>
     * <p>
     * <b>示例</b>：
     * 
     * <pre>
     * // 只接收推理和最终结构化数据
     * {"eventFilter": ["thinking", "structured"]}
     *
     * // 接收所有事件类型
     * {"eventFilter": ["thinking", "content", "hint", "summary", "structured"]}
     *
     * // 默认行为：接收所有事件类型
     * {"eventFilter": null}
     * </pre>
     * </p>
     */
    private List<String> eventFilter;

    /**
     * 请求取消令牌（可选）
     * <p>
     * 仅在流式输出时使用。
     * 前端可以使用此令牌主动取消正在进行的流式请求。
     * </p>
     * <p>
     * <b>使用方式</b>：
     * <ol>
     * <li>发起请求时，不提供此参数（系统自动生成）</li>
     * <li>在响应的开始事件中会包含 requestId（请求ID）</li>
     * <li>调用 POST /api/conversations/cancel/{requestId} 取消请求</li>
     * </ol>
     * </p>
     */
    private String cancelToken;

    /**
     * Schema 名称（可选）
     * <p>
     * 仅在 {@code structured=true} 时生效。
     * <p>
     * <b>使用场景</b>：当 Agent 配置了多个 Schema 时，通过此参数选择使用哪个 Schema。
     * </p>
     * <p>
     * <b>优先级</b>：
     * <ol>
     * <li>如果提供了 schema 参数（动态 Schema），优先使用动态 Schema</li>
     * <li>否则，如果提供了 schemaName，从 Agent 配置的多 Schema 中选择</li>
     * <li>否则，使用 Agent 配置的默认 schema_class</li>
     * </ol>
     * </p>
     * <p>
     * <b>示例</b>：
     * 
     * <pre>
     * {
     *   "message": "分析营养数据",
     *   "mode": "sync",
     *   "structured": true,
     *   "schemaName": "nutritionAnalysis"
     * }
     * </pre>
     * </p>
     */
    private String schemaName;

    /**
     * 是否启用思考过程输出（默认 false）
     * <p>
     * 仅在 mode=stream 时生效。
     * </p>
     */
    private Boolean enableThinking = false;

    /**
     * 流式输出的分块大小（默认 50 字符）
     * <p>
     * 仅在 mode=stream 时生效。
     * </p>
     */
    private Integer chunkSize = 50;

    // ==================== 结构化输出参数（可选） ====================

    /**
     * 结构化输出的 JSON Schema（动态定义）
     * <p>
     * 仅在 {@code structured=true} 时生效。
     * <p>
     * <b>优先级</b>：
     * <ol>
     * <li>如果 Agent 配置了 schema_class，使用配置的 Schema 类（推荐）</li>
     * <li>否则，使用此参数提供的动态 JSON Schema</li>
     * </ol>
     * </p>
     * <p>
     * <b>使用示例</b>:
     * 
     * <pre>
     * {
     *   "message": "提取信息",
     *   "mode": "sync",
     *   "structured": true,
     *   "schema": {
     *     "type": "object",
     *     "properties": {
     *       "name": {"type": "string"},
     *       "age": {"type": "integer"}
     *     }
     *   }
     * }
     * </pre>
     * </p>
     */
    private Map<String, Object> schema;

    // ==================== 异步任务参数（可选） ====================

    /**
     * 是否异步执行（默认 false）
     * <p>
     * <b>true</b>：立即返回任务 ID，异步执行，通过查询接口获取结果
     * <br>
     * <b>false</b>：同步执行，直接返回结果
     * </p>
     */
    private Boolean async = false;

    /**
     * 异步任务的超时时间（秒，默认 300）
     * <p>
     * 仅在 async=true 时生效。
     * </p>
     */
    private Integer asyncTimeout = 300;

    // ==================== 多模态参数（可选） ====================

    /**
     * 附件列表（图片、文档等）
     * <p>
     * 用于多模态对话，支持图片识别、文档解析等场景。
     * </p>
     */
    private List<AttachmentDto> attachments;

    // ==================== 高级参数（可选） ====================

    /**
     * 自定义参数
     * <p>
     * 用于传递特定场景下的自定义配置。
     * </p>
     */
    private Map<String, Object> parameters;

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

    // ==================== RAG 配置（可选） ====================

    /**
     * RAG 模式（默认 NONE）
     * <p>
     * <ul>
     * <li>GENERIC：自动检索并注入知识到对话中</li>
     * <li>AGENTIC：Agent 通过工具主动检索知识</li>
     * <li>NONE：禁用 RAG 功能</li>
     * </ul>
     * </p>
     */
    private String ragMode = "NONE";

    /**
     * 知识库名称列表（可选）
     * <p>
     * 指定本次对话要使用的知识库。
     * 如果不提供，使用 Agent 配置的所有知识库。
     * </p>
     */
    private List<String> knowledgeBases;

    /**
     * RAG 检索的最大文档数（可选，默认 5）
     */
    private Integer retrieveLimit;

    /**
     * RAG 检索的相似度阈值（可选，默认 0.5）
     */
    private Double retrieveScoreThreshold;

    // ==================== MCP 配置（可选） ====================

    /**
     * MCP 服务器名称列表（可选）
     * <p>
     * 指定本次对话要启用的 MCP 工具服务器。
     * </p>
     */
    private List<String> mcpServers;

    // ==================== Tool 配置（可选） ====================

    /**
     * 工具名称列表（可选）
     */
    private List<String> enabledTools;

    /**
     * 禁用的工具列表（可选）
     */
    private List<String> disabledTools;

    /**
     * 启用的工具组列表（可选）
     * <p>
     * 用于渐进式暴露工具，控制 LLM 可见的工具范围。
     * 可选值：search, file, database, code, mcp
     * </p>
     */
    private List<String> enabledToolGroups;

    // ==================== Skill 配置（可选） ====================

    /**
     * 启用的技能列表（可选）
     */
    private List<String> enabledSkills;

    // ==================== Memory 配置（可选） ====================

    /**
     * 长期记忆配置（可选）
     */
    private Map<String, Object> longTermMemory;

    /**
     * 长期记忆模式（可选，默认 BOTH）
     */
    private String longTermMemoryMode = "BOTH";

    // ==================== 高级执行参数（可选） ====================

    /**
     * 最大推理迭代次数（默认使用 Agent 配置）
     */
    private Integer maxIters;

    /**
     * 是否启用元工具（默认使用 Agent 配置）
     */
    private Boolean enableMetaTool;

    /**
     * 是否启用计划记录（PlanNotebook）
     * <p>
     * 启用后 Agent 会自动将复杂任务拆解为子任务并逐步执行
     * </p>
     */
    private Boolean enablePlanNotebook;

    /**
     * 是否启用 A2A (Agent-to-Agent) 模式（默认 false）
     * <p>
     * <b>true</b>：启用 A2A 模式，Supervisor Agent 会协调多个专家 Agent 协作完成任务
     * - 适合复杂任务，如食谱生成（需要菜品搜索、营养评估、食谱编排等多个专家协作）
     * - 响应时间较长，但结果更精准
     * <br>
     * <b>false</b>：禁用 A2A 模式，直接使用单一 Agent 响应
     * - 响应速度快，适合简单问答
     * </p>
     */
    private Boolean enableA2A = false;

    /**
     * 响应深度模式（默认 "standard"）
     *
     * <p>
     * 控制对话流程的开销级别：
     * </p>
     * <ul>
     * <li><b>quick</b>：快速模式 — 跳过 RAG、记忆、场景检测，直接调用 Agent</li>
     * <li><b>standard</b>：标准模式 — 完整流程（记忆 + RAG + 场景检测）</li>
     * <li><b>deep</b>：深度模式 — 标准流程 + A2A 心跳 + 更长超时</li>
     * </ul>
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
     * A2A 模式下的超时时间（秒，默认 120）
     * <p>
     * 仅在 enableA2A=true 时生效。
     * 由于 A2A 涉及多个 Agent 协作，需要更长的超时时间。
     * </p>
     */
    private Integer a2aTimeout = 120;

    /**
     * 温度参数（0-2.0，默认使用 Agent 配置）
     */
    private Double temperature;

    /**
     * 最大 Token 数（默认使用 Agent 配置）
     */
    private Integer maxTokens;

    /**
     * Top-P 采样参数（0-1，默认使用 Agent 配置）
     */
    private Double topP;

    /**
     * 存在惩罚（-2.0 到 2.0，默认使用 Agent 配置）
     */
    private Double presencePenalty;

    /**
     * 频率惩罚（-2.0 到 2.0，默认使用 Agent 配置）
     */
    private Double frequencyPenalty;

    /**
     * 停止序列列表（可选）
     */
    private List<String> stopSequences;

    // ==================== 内部使用字段 ====================

    /**
     * 内部字段：是否包含历史消息
     */
    private transient Boolean includeHistory;

    /**
     * 内部字段：响应语言
     */
    private String language;

    // ==================== 构造方法 ====================

    /**
     * 默认构造函数
     */
    public UnifiedChatRequest() {
    }

    /**
     * 带消息的构造函数
     *
     * @param message 用户消息
     */
    public UnifiedChatRequest(String message) {
        this.message = message;
    }

    /**
     * 带消息和Agent名称的构造函数
     *
     * @param message   用户消息
     * @param agentName Agent 名称
     */
    public UnifiedChatRequest(String message, String agentName) {
        this.message = message;
        this.agentName = agentName;
    }

    // ==================== 工具方法 ====================

    /**
     * 判断是否为流式模式
     *
     * @return true-流式模式，false-同步模式
     */
    public boolean isStreamMode() {
        return "stream".equals(mode);
    }

    /**
     * 判断是否为同步模式
     *
     * @return true-同步模式，false-流式模式
     */
    public boolean isSyncMode() {
        return "sync".equals(mode);
    }

    /**
     * 判断是否为结构化输出
     *
     * @return true-结构化输出，false-普通输出
     */
    public boolean isStructuredOutput() {
        return Boolean.TRUE.equals(structured);
    }

    /**
     * 判断是否需要会话管理
     *
     * @return true-需要会话管理，false-不需要
     */
    public boolean needsConversation() {
        return conversationId != null ||
                (Boolean.TRUE.equals(autoManageConversation) && userId != null);
    }

    /**
     * 判断是否有附件
     *
     * @return true-有附件，false-无附件
     */
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    /**
     * 获取有效的 Agent 名称
     *
     * @param defaultAgentName 默认 Agent 名称
     * @return 有效的 Agent 名称
     */
    public String getEffectiveAgentName(String defaultAgentName) {
        if (agentName != null && !agentName.isBlank()) {
            return agentName;
        }
        return defaultAgentName;
    }

    /**
     * 获取最大历史消息数
     *
     * @return 最大历史消息数
     */
    public Integer getMaxHistory() {
        return 10;
    }

    public String getResponseMode() {
        return responseMode;
    }

    public void setResponseMode(String responseMode) {
        this.responseMode = responseMode;
    }

    public boolean isQuickMode() {
        return "quick".equalsIgnoreCase(responseMode);
    }

    public boolean isDeepMode() {
        return "deep".equalsIgnoreCase(responseMode);
    }
}
