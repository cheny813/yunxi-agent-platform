package io.yunxi.platform.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 网关会话实体
 *
 * <p>维护消息平台 chat_id 与 agent-core conversationId 的映射关系</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewaySession {

    /** 会话键（唯一标识），格式：{platform}:dm:{chatId} 或 {platform}:group:{chatId}:{userId} */
    private String sessionKey;

    /** 来源平台 */
    private PlatformType platform;

    /** 平台会话 ID（企微 chat_id / 钉钉 conversationId 等） */
    private String chatId;

    /** 会话类型：dm / group / channel */
    private String chatType;

    /** 用户 ID（平台侧） */
    private String userId;

    /** 用户显示名 */
    private String userName;

    /** agent-core 侧的 conversationId */
    private String conversationId;

    /** 使用的 Agent 名称 */
    private String agentName;

    /** 使用的模型名称（可选覆盖） */
    private String modelOverride;

    /** 会话创建时间 */
    private Instant createdAt;

    /** 最后活跃时间 */
    private Instant lastActiveAt;

    /** 消息计数 */
    private long messageCount;

    /** 扩展属性 */
    private Map<String, Object> extra;

    public void touch() {
        this.lastActiveAt = Instant.now();
        this.messageCount++;
    }
}
