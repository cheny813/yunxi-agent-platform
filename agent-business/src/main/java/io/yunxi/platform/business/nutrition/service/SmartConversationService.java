package io.yunxi.platform.business.nutrition.service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.platform.framework.conversation.ConversationDomainService;
import io.yunxi.platform.shared.dto.CreateConversationRequest;
import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 智能会话管理服务
 *
 * <p>
 * 本服务提供智能会话管理功能，自动创建和复用会话
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
public class SmartConversationService {

    /** 会话领域服务 */
    private final ConversationDomainService conversationDomainService;

    /**
     * 构造方法
     *
     * @param conversationDomainService 会话领域服务
     */
    public SmartConversationService(ConversationDomainService conversationDomainService) {
        this.conversationDomainService = conversationDomainService;
    }

    /**
     * 获取或创建会话，若会话ID有效则复用，否则创建新会话
     *
     * @param conversationId 会话ID，可为 null
     * @param request        创建会话请求参数
     * @return 会话实体
     */
    public ConversationEntity getOrCreateConversation(String conversationId,
            CreateConversationRequest request) {
        if (conversationId != null && !conversationId.isBlank()) {
            try {
                return conversationDomainService.getConversation(conversationId);
            } catch (Exception e) {
                log.debug("会话不存在或已过期，创建新会话: {}", conversationId);
            }
        }
        return createNewConversation(request);
    }

    /**
     * 创建新会话
     *
     * @param request 创建会话请求参数
     * @return 新创建的会话实体
     */
    public ConversationEntity createNewConversation(CreateConversationRequest request) {
        var info = conversationDomainService.createConversation(request);
        return conversationDomainService.getConversation(info.getId());
    }

    /**
     * 根据首条消息生成会话标题，截取前20个字符
     *
     * @param firstMessage 首条消息内容
     * @return 生成的会话标题
     */
    public String generateTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return "新对话";
        }

        String cleaned = firstMessage
                .replaceAll("[，。！？；：、\"\"''（）\\[\\]《》]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (cleaned.length() <= 20) {
            return cleaned;
        }

        String[] words = cleaned.split(" ");
        StringBuilder title = new StringBuilder();
        for (String word : words) {
            if (title.length() + word.length() > 20) {
                break;
            }
            if (title.length() > 0) {
                title.append(" ");
            }
            title.append(word);
        }

        return title.toString();
    }

    /**
     * 从消息列表中提取首条用户消息并生成标题
     *
     * @param messages 消息列表
     * @return 生成的会话标题
     */
    public String generateTitleFromMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "新对话";
        }

        for (Msg msg : messages) {
            if (msg.getRole() == MsgRole.USER) {
                return generateTitle(msg.getTextContent());
            }
        }
        return "新对话";
    }
}
