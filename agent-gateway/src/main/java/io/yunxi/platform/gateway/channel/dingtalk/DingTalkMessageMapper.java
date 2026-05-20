package io.yunxi.platform.gateway.channel.dingtalk;

import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import io.yunxi.platform.gateway.channel.MessageContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 钉钉消息格式转换
 *
 * <p>
 * 支持两种输入格式：
 * </p>
 * <ul>
 *   <li>{@link ChatbotMessage}：dingtalk-stream SDK 类型化回调（推荐）</li>
 *   <li>{@code Map<String, Object>}：旧版/通用格式兼容</li>
 * </ul>
 */
@Slf4j
public class DingTalkMessageMapper {

    // ===== ChatbotMessage（SDK 类型化回调，推荐） =====

    /**
     * 将 SDK ChatbotMessage 转换为 MessageContext
     */
    public static MessageContext toMessageContext(ChatbotMessage message) {
        String userId = message.getSenderStaffId() != null
                ? message.getSenderStaffId()
                : message.getSenderId();
        String chatType = "1".equals(message.getConversationType()) ? "dm" : "group";

        return MessageContext.builder()
                .platform(io.yunxi.platform.gateway.model.PlatformType.DINGTALK)
                .chatId(message.getConversationId())
                .chatType(chatType)
                .userId(userId)
                .userName(message.getSenderNick())
                .build();
    }

    /**
     * 从 ChatbotMessage 提取文本内容
     */
    public static String extractText(ChatbotMessage message) {
        String msgType = message.getMsgtype();
        if ("text".equals(msgType)) {
            MessageContent text = message.getText();
            return text != null ? text.getContent() : null;
        } else if ("richText".equals(msgType)) {
            MessageContent content = message.getContent();
            return content != null ? extractPlainTextFromRichContent(content.getRichText()) : null;
        }
        log.debug("[DingTalkMapper] 暂不支持的消息类型: {}", msgType);
        return null;
    }

    private static String extractPlainTextFromRichContent(List<MessageContent> richText) {
        if (richText == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (MessageContent paragraph : richText) {
            List<MessageContent> elements = paragraph.getRichText();
            if (elements != null) {
                for (MessageContent elem : elements) {
                    String text = elem.getText();
                    if (text != null) {
                        sb.append(text);
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ===== Map<String, Object>（兼容旧版格式） =====
    public static MessageContext toMessageContext(Map<String, Object> data) {
        // senderId 在新版本 SDK 中可能是字符串（staffId）或 Map
        String userId = extractSenderId(data);
        String userName = (String) data.get("senderNick");
        String chatId = (String) data.get("conversationId");
        String chatType = "1".equals(String.valueOf(data.get("conversationType"))) ? "dm" : "group";

        return MessageContext.builder()
                .platform(io.yunxi.platform.gateway.model.PlatformType.DINGTALK)
                .chatId(chatId)
                .chatType(chatType)
                .userId(userId)
                .userName(userName)
                .extra(data)
                .build();
    }

    /**
     * 提取发送者 ID
     *
     * <p>
     * 兼容两种格式：
     * - 新版 SDK: senderId 为字符串（staffId）
     * - 旧版格式: senderId 为 Map，包含 unionId/staffId 字段
     * </p>
     */
    private static String extractSenderId(Map<String, Object> data) {
        Object senderId = data.get("senderId");
        if (senderId == null) {
            return null;
        }
        if (senderId instanceof String) {
            return (String) senderId;
        }
        if (senderId instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> senderMap = (Map<String, Object>) senderId;
            // 优先使用 staffId（企业内部用户），其次 unionId
            String staffId = (String) senderMap.get("staffId");
            if (staffId != null && !staffId.isBlank()) {
                return staffId;
            }
            return (String) senderMap.get("unionId");
        }
        return String.valueOf(senderId);
    }

    /**
     * 从钉钉消息体中提取文本内容
     */
    public static String extractText(Map<String, Object> data) {
        String msgType = (String) data.get("msgtype");
        if ("text".equals(msgType)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> text = (Map<String, Object>) data.get("text");
            return text != null ? (String) text.get("content") : null;
        } else if ("richText".equals(msgType)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> richText = (Map<String, Object>) data.get("richText");
            return richText != null ? extractPlainTextFromRich(richText) : null;
        }
        log.debug("[DingTalkMapper] 暂不支持的消息类型: {}", msgType);
        return null;
    }

    private static String extractPlainTextFromRich(Map<String, Object> richText) {
        StringBuilder sb = new StringBuilder();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> paragraphs = (java.util.List<Map<String, Object>>) richText.get("richText");
        if (paragraphs != null) {
            for (Map<String, Object> para : paragraphs) {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> elements = (java.util.List<Map<String, Object>>) para
                        .get("elements");
                if (elements != null) {
                    for (Map<String, Object> elem : elements) {
                        String text = (String) elem.get("text");
                        if (text != null) {
                            sb.append(text);
                        }
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString().trim();
    }
}
