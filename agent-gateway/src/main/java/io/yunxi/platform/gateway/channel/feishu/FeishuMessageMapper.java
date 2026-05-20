package io.yunxi.platform.gateway.channel.feishu;

import io.yunxi.platform.gateway.channel.MessageContext;
import io.yunxi.platform.gateway.model.PlatformType;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 飞书消息格式转换
 */
@Slf4j
public class FeishuMessageMapper {

    /**
     * 将飞书事件转换为 MessageContext
     */
    public static MessageContext toMessageContext(Map<String, Object> event) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sender = (Map<String, Object>) event.get("sender");
        @SuppressWarnings("unchecked")
        Map<String, Object> senderId = sender != null ? (Map<String, Object>) sender.get("sender_id") : null;

        String userId = senderId != null ? (String) senderId.get("union_id") : null;
        String userName = sender != null ? (String) sender.get("nickname") : null;
        String chatId = (String) event.get("chat_id");
        String chatType = (String) event.get("chat_type"); // "p2p" / "group"

        // 归一化 chatType
        String normalizedChatType = "p2p".equals(chatType) ? "dm" : "group";

        return MessageContext.builder()
                .platform(PlatformType.FEISHU)
                .chatId(chatId)
                .chatType(normalizedChatType)
                .userId(userId)
                .userName(userName)
                .build();
    }

    /**
     * 从飞书消息体中提取文本内容
     */
    public static String extractText(Map<String, Object> event) {
        String msgType = (String) event.get("msg_type");
        if ("text".equals(msgType)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) event.get("content");
            return content != null ? (String) content.get("text") : null;
        } else if ("post".equals(msgType)) {
            // 富文本提取纯文本
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) event.get("content");
            return content != null ? extractPlainTextFromPost(content) : null;
        }
        log.debug("[FeishuMapper] 暂不支持的消息类型: {}", msgType);
        return null;
    }

    private static String extractPlainTextFromPost(Map<String, Object> content) {
        StringBuilder sb = new StringBuilder();
        @SuppressWarnings("unchecked")
        Map<String, Object> post = (Map<String, Object>) content.get("content");
        if (post == null) return null;

        // 遍历多语言内容
        for (Object value : post.values()) {
            if (value instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<java.util.List<Map<String, Object>>> lines = (java.util.List<java.util.List<Map<String, Object>>>) value;
                for (java.util.List<Map<String, Object>> line : lines) {
                    for (Map<String, Object> elem : line) {
                        String tag = (String) elem.get("tag");
                        if ("text".equals(tag) || "a".equals(tag)) {
                            String text = (String) elem.get("text");
                            if (text != null) sb.append(text);
                        }
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 构造飞书文本回复消息体
     */
    public static Map<String, Object> buildTextReply(String text) {
        return Map.of(
                "msg_type", "text",
                "content", Map.of("text", text)
        );
    }

    /**
     * 构造飞书 Markdown 回复消息体
     */
    public static Map<String, Object> buildMarkdownReply(String content) {
        return Map.of(
                "msg_type", "interactive",
                "card", Map.of(
                        "elements", java.util.List.of(
                                Map.of("tag", "markdown", "content", content)
                        )
                )
        );
    }
}
