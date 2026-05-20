package io.yunxi.platform.gateway.channel.wecom;

import io.yunxi.platform.gateway.channel.MessageContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 企业微信消息格式转换
 *
 * <p>负责企微 WebSocket 协议消息与网关内部格式的互转</p>
 */
@Slf4j
public class WeComMessageMapper {

    /**
     * 将企微收到的消息转换为 MessageContext
     *
     * @param payload 企微 WebSocket 消息体
     */
    public static MessageContext toMessageContext(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) payload.get("from");
        @SuppressWarnings("unchecked")
        Map<String, Object> chatInfo = (Map<String, Object>) payload.get("chat_info");

        String userId = from != null ? (String) from.get("user_id") : null;
        String userName = from != null ? (String) from.get("name") : null;
        String chatId = chatInfo != null ? (String) chatInfo.get("chat_id") : null;
        String chatType = chatInfo != null ? (String) chatInfo.get("chat_type") : "single";

        // 企微 chat_type: single=DM, group=群聊
        String normalizedChatType = "single".equals(chatType) ? "dm" : "group";

        return MessageContext.builder()
                .platform(io.yunxi.platform.gateway.model.PlatformType.WECOM)
                .chatId(chatId)
                .chatType(normalizedChatType)
                .userId(userId)
                .userName(userName)
                .build();
    }

    /**
     * 从企微消息体中提取文本内容
     */
    public static String extractText(Map<String, Object> payload) {
        String msgType = (String) payload.get("msg_type");
        if ("text".equals(msgType)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> text = (Map<String, Object>) payload.get("text");
            return text != null ? (String) text.get("content") : null;
        }
        // 其他类型暂不支持
        log.debug("[WeComMapper] 暂不支持的消息类型: {}", msgType);
        return null;
    }

    /**
     * 构造企微发送消息的 payload
     */
    public static Map<String, Object> buildSendPayload(String chatId, String chatType, String text, String callbackId) {
        return Map.of(
                "cmd", "aibot_send_msg",
                "headers", Map.of("req_id", java.util.UUID.randomUUID().toString()),
                "body", Map.of(
                        "msg_type", "text",
                        "text", Map.of("content", text),
                        "chat_info", Map.of(
                                "chat_id", chatId,
                                "chat_type", "single".equals(chatType) ? "single" : "group"
                        ),
                        "callback_id", callbackId != null ? callbackId : ""
                )
        );
    }

    /**
     * 构造 Markdown 格式的发送消息 payload
     */
    public static Map<String, Object> buildMarkdownPayload(String chatId, String chatType, String markdown, String callbackId) {
        return Map.of(
                "cmd", "aibot_send_msg",
                "headers", Map.of("req_id", java.util.UUID.randomUUID().toString()),
                "body", Map.of(
                        "msg_type", "markdown",
                        "markdown", Map.of("content", markdown),
                        "chat_info", Map.of(
                                "chat_id", chatId,
                                "chat_type", "single".equals(chatType) ? "single" : "group"
                        ),
                        "callback_id", callbackId != null ? callbackId : ""
                )
        );
    }

    /**
     * 将长文本按企微消息长度限制分段
     */
    public static java.util.List<String> splitMessage(String text, int maxLength) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
        if (text.length() <= maxLength) {
            parts.add(text);
            return parts;
        }

        // 按段落分割，尽量不截断
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLength, text.length());
            // 尝试在换行处分割
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > start) {
                    end = lastNewline + 1;
                }
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }
}
