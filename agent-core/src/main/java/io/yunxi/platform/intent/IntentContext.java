package io.yunxi.platform.intent;

import java.util.List;
import io.agentscope.core.message.Msg;

/**
 * 意图分析上下文。
 *
 * @param query          原始用户消息（必填）
 * @param agentName      目标 Agent 名称（会话绑定）
 * @param userId         用户 ID（可 null）
 * @param conversationId 会话 ID（可 null）
 * @param recentMessages 最近 N 轮消息（指代消解用；M1 不消费，M2 使用；可为空列表）
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record IntentContext(
        String query,
        String agentName,
        String userId,
        String conversationId,
        List<Msg> recentMessages) {
}
