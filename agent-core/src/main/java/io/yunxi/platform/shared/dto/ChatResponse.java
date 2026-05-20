package io.yunxi.platform.shared.dto;

/**
 * 对话响应数据传输对象
 *
 * <p>
 * 封装 Agent 对用户消息的回复内容。这是一个简化的响应模型，
 * 仅包含核心的回复文本。对于更复杂的响应场景（如流式输出、
 * 结构化输出），请使用 {@link UnifiedChatResponse}。
 * </p>
 *
 * <h3>使用场景</h3>
 * <ul>
 * <li>简单的问答场景</li>
 * <li>同步对话 API 响应</li>
 * <li>测试和调试</li>
 * </ul>
 *
 * <h3>响应示例</h3>
 *
 * <pre>
 * {
 *   "reply": "Spring Boot 是一个基于 Spring 框架的快速开发工具...",
 *   "conversationId": "conv-xxx-yyy"
 * }
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @see ChatRequest 对话请求
 * @see UnifiedChatResponse 统一对话响应（支持更多功能）
 */
public class ChatResponse {

    /**
     * Agent 回复文本
     *
     * <p>
     * Agent 处理用户消息后生成的自然语言响应。
     * 内容取决于 Agent 的系统提示词、上下文和用户输入。
     * </p>
     */
    private String reply;

    /**
     * 会话 ID（如果使用会话模式）
     *
     * <p>
     * 如果对话基于会话进行，返回会话 ID。
     * 可用于后续对话时延续历史。
     * </p>
     */
    private String conversationId;

    /**
     * 默认构造函数
     */
    public ChatResponse() {
    }

    /**
     * 便捷构造函数：创建包含回复内容的响应
     *
     * @param reply Agent 的回复文本
     */
    public ChatResponse(String reply) {
        this.reply = reply;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
}
