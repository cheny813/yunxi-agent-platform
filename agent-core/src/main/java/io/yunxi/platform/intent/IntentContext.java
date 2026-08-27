package io.yunxi.platform.intent;

import java.util.List;
import io.agentscope.core.message.Msg;

/**
 * 意图分析上下文。
 *
 * <p>领域判定链：显式 domain（本 record 最高优先级，信任调用方）→
 * 规则匹配（agent 前缀 / profile 归属，DomainResolver 消费）→ default → base。
 * {@link #domain()} 为 null 时表示调用方未显式指定，交由 {@code DomainResolver} 按规则链解析。</p>
 *
 * @param query          原始用户消息（必填）
 * @param agentName      目标 Agent 名称（会话绑定）
 * @param userId         用户 ID（可 null）
 * @param conversationId 会话 ID（可 null）
 * @param recentMessages 最近 N 轮消息（指代消解用；可为空列表）
 * @param domain         显式指定领域名（可 null，判定链最高优先级；非流式入口无 profile 字段时
 *                       可借此显式传域）
 * @param profile        Profile 名称（可 null，参与域规则匹配；ConversationChatRequest 无
 *                       profile 字段，非流式入口传 null）
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record IntentContext(
        String query,
        String agentName,
        String userId,
        String conversationId,
        List<Msg> recentMessages,
        String domain,
        String profile) {
}
