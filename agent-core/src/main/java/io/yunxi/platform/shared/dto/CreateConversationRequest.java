package io.yunxi.platform.shared.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建会话请求 DTO
 *
 * <p>
 * 用于创建新的会话对象，建立用户与 Agent 之间的对话上下文。
 * </p>
 *
 * <p>
 * 使用场景：
 * <ul>
 * <li>用户开始一个新的对话会话</li>
 * <li>为特定的 Agent 创建专属会话</li>
 * <li>为不同用户创建隔离的会话空间</li>
 * <li>自定义会话的过期时间和标题</li>
 * </ul>
 * </p>
 *
 * <p>
 * 与 ConversationChatRequest 的区别：
 * <ul>
 * <li>CreateConversationRequest：创建新会话，返回会话 ID，用于后续对话</li>
 * <li>ConversationChatRequest：在已有会话中进行对话，需要会话 ID</li>
 * </ul>
 * </p>
 *
 * <p>
 * 典型使用流程：
 * <ol>
 * <li>调用 POST /conversations 创建会话，获得 conversationId</li>
 * <li>使用 conversationId 调用 POST /conversations/{id}/chat 进行对话</li>
 * <li>重复步骤 2，直到对话结束或会话过期</li>
 * </ol>
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class CreateConversationRequest {

    /**
     * Agent 名称（必填）
     *
     * <p>
     * 指定要使用的 Agent 的唯一名称。
     * 必须是系统中已配置的 Agent。
     * 示例："chat-assistant", "code-expert"
     * </p>
     *
     * <p>
     * 验证规则：不能为空或空串
     * </p>
     */
    @NotBlank(message = "agentName 不能为空")
    private String agentName;

    /**
     * 用户 ID（可选）
     *
     * <p>
     * 用于多租户场景，标识会话所属的用户。
     * </p>
     *
     * <p>
     * 使用场景：
     * <ul>
     * <li>区分不同用户的会话，实现用户隔离</li>
     * <li>查询特定用户的会话列表</li>
     * <li>多用户环境下的权限控制</li>
     * </ul>
     * </p>
     *
     * <p>
     * 如果为 null 或空字符串，表示匿名会话或未指定用户。
     * </p>
     */
    private String userId;

    /**
     * 会话标题（可选）
     *
     * <p>
     * 会话的显示名称或描述。
     * </p>
     *
     * <p>
     * 使用场景：
     * <ul>
     * <li>在会话列表中快速识别会话内容</li>
     * <li>给会话命名，便于管理多个会话</li>
     * <li>根据用户意图自动生成（如"代码生成会话"）</li>
     * </ul>
     * </p>
     *
     * <p>
     * 如果不指定，系统可能会根据第一条消息自动生成标题。
     * </p>
     */
    private String title;

    /**
     * 会话过期时长（小时）
     *
     * <p>
     * 会话的有效期，超过此时间会话将被自动清理。
     * </p>
     *
     * <p>
     * 使用场景：
     * <ul>
     * <li>短期会话：设置为 1-2 小时，适合临时对话</li>
     * <li>长期会话：设置为 48-72 小时，适合复杂任务</li>
     * <li>默认值：24 小时，平衡存储和性能</li>
     * </ul>
     * </p>
     *
     * <p>
     * 值必须大于 0。
     * </p>
     */
    private Integer expirationHours = 24;

    /**
     * 默认构造函数
     *
     * <p>
     * 创建一个空的请求对象。
     * 默认值：expirationHours = 24
     * </p>
     */
    public CreateConversationRequest() {
    }

    /**
     * 带参构造函数
     *
     * <p>
     * 快速创建包含必填字段的请求对象。
     * </p>
     *
     * @param agentName Agent 名称（必填）
     * @param userId    用户 ID（可选）
     */
    public CreateConversationRequest(String agentName, String userId) {
        this.agentName = agentName;
        this.userId = userId;
    }

    // ==================== Getter 和 Setter 方法 ====================

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getExpirationHours() {
        return expirationHours;
    }

    public void setExpirationHours(Integer expirationHours) {
        this.expirationHours = expirationHours;
    }
}
