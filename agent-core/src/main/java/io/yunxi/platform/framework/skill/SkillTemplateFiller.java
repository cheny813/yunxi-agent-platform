package io.yunxi.platform.framework.skill;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.framework.skill.model.SkillTemplate;
import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 模板填充器
 * <p>
 * 从成功对话中自动提取信息填充 SkillTemplate。
 * 当 LLM 生成的模板存在空缺字段时，基于对话内容自动补全。
 * </p>
 *
 * <h3>自动填充的字段</h3>
 * <ul>
 * <li>name: 从对话主题提取</li>
 * <li>description: 从对话摘要提取</li>
 * <li>parameters: 从工具调用提取</li>
 * <li>examples: 从成功对话提取示例</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @since 2026-04-14
 */
@Slf4j
@Component
public class SkillTemplateFiller {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";

    /**
     * 自动填充模板
     * <p>
     * 仅填充模板中为空的字段，不会覆盖 LLM 已生成的内容。
     * </p>
     *
     * @param template     技能模板
     * @param conversation 成功对话
     * @return 填充后的模板
     */
    public SkillTemplate fillTemplate(SkillTemplate template, ConversationEntity conversation) {
        if (template == null || conversation == null) {
            return template;
        }

        List<Msg> messages = conversation.getMessages();
        if (messages == null || messages.isEmpty()) {
            log.warn("对话消息为空，跳过模板填充: conversationId={}", conversation.getId());
            return template;
        }

        try {
            // 提取首条用户消息和首条助手回复（用于字段填充）
            String firstUserMsg = extractFirstMessageByRole(messages, ROLE_USER);
            String firstAssistantMsg = extractFirstMessageByRole(messages, ROLE_ASSISTANT);

            // 填充名称
            if (template.getName() == null || template.getName().isBlank()) {
                template.setName(extractSkillName(firstUserMsg));
            }

            // 填充描述
            if (template.getDescription() == null || template.getDescription().isBlank()) {
                template.setDescription(extractDescription(firstAssistantMsg));
            }

            // 填充参数说明
            if (template.getParameters() == null || template.getParameters().isEmpty()) {
                template.setParameters(extractParameters(firstAssistantMsg));
            }

            // 填充示例
            if (template.getExamples() == null || template.getExamples().isEmpty()) {
                template.setExamples(extractExamples(firstUserMsg, firstAssistantMsg));
            }

            log.info("模板填充完成: name={}", template.getName());

        } catch (Exception e) {
            log.error("模板填充失败: {}", e.getMessage());
        }

        return template;
    }

    /**
     * 提取指定角色的第一条消息内容
     *
     * @param messages 消息列表
     * @param role     角色标识（"USER" 或 "ASSISTANT"）
     * @return 消息文本内容，不存在则返回 null
     */
    private String extractFirstMessageByRole(List<Msg> messages, String role) {
        return messages.stream()
                .filter(m -> m.getRole() != null && role.equals(m.getRole().toString()))
                .map(Msg::getContent)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst()
                .orElse(null);
    }

    /**
     * 从用户消息提取技能名称
     *
     * @param userMessage 首条用户消息
     * @return 技能名称
     */
    private String extractSkillName(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "auto-skill-" + System.currentTimeMillis();
        }

        // 提取前 50 字符作为名称基础
        String name = userMessage.substring(0, Math.min(50, userMessage.length()));
        // 清理特殊字符
        name = name.replaceAll("[^\\w\\s\\u4e00-\\u9fff]", "").trim();
        name = name.replaceAll("\\s+", "-").toLowerCase();

        return name.isBlank() ? "auto-skill-" + System.currentTimeMillis() : name;
    }

    /**
     * 从助手回复提取描述
     *
     * @param assistantMessage 首条助手回复
     * @return 技能描述
     */
    private String extractDescription(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.isBlank()) {
            return "";
        }

        // 取前 200 字符
        int length = Math.min(200, assistantMessage.length());
        String desc = assistantMessage.substring(0, length);

        // 清理换行
        desc = desc.replace("\n", " ").trim();

        return desc;
    }

    /**
     * 从助手回复提取参数
     * <p>
     * 检测 JSON 格式的键值对作为参数说明
     * </p>
     *
     * @param assistantMessage 首条助手回复
     * @return 参数映射
     */
    private Map<String, String> extractParameters(String assistantMessage) {
        Map<String, String> params = new HashMap<>();

        if (assistantMessage == null || assistantMessage.isBlank()) {
            return params;
        }

        // 检测参数模式（如 JSON 格式）
        Pattern paramPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = paramPattern.matcher(assistantMessage);

        int count = 0;
        while (matcher.find() && count < 5) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            params.put(key, value);
            count++;
        }

        return params;
    }

    /**
     * 从用户消息和助手回复提取示例
     *
     * @param userMessage      首条用户消息
     * @param assistantMessage 首条助手回复
     * @return 示例列表
     */
    private List<String> extractExamples(String userMessage, String assistantMessage) {
        List<String> examples = new ArrayList<>();

        // 用户消息作为示例输入
        if (userMessage != null && !userMessage.isBlank()) {
            examples.add("用户说: " + truncate(userMessage, 100));
        }

        // AI 响应作为示例输出（截取）
        if (assistantMessage != null && !assistantMessage.isBlank()) {
            examples.add("AI 回复: " + truncate(assistantMessage, 150));
        }

        return examples;
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
