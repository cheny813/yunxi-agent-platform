package io.yunxi.platform.infra.persistence;

import io.yunxi.platform.framework.conversation.ConversationDomainService;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.mapper.ConversationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 异步会话持久化服务
 *
 * <p>
 * 将会话保存等耗时操作异步化，确保响应快速返回。
 * 长期记忆由框架的 StaticLongTermMemoryHook 在 Agent 生命周期内自动管理。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.2.0
 */
@Slf4j
@Service
public class AsyncConversationPersistenceService {

    /** 会话 Mapper */
    private final ConversationMapper conversationMapper;
    /** 会话领域服务 */
    private final ConversationDomainService conversationDomainService;

    /**
     * 构造异步会话持久化服务
     *
     * @param conversationMapper        会话 Mapper
     * @param conversationDomainService 会话领域服务
     */
    public AsyncConversationPersistenceService(
            ConversationMapper conversationMapper,
            ConversationDomainService conversationDomainService) {
        this.conversationMapper = conversationMapper;
        this.conversationDomainService = conversationDomainService;
    }

    /**
     * 异步保存会话到数据库
     *
     * <p>
     * 此方法会在后台线程执行，不阻塞主流程
     * </p>
     *
     * @param conversation   会话实体
     * @param conversationId 会话ID
     */
    @Async("asyncExecutor")
    public void persistConversationAsync(
            ConversationEntity conversation,
            String conversationId) {
        try {
            // 1. 保存会话到数据库
            int rows = conversationMapper.save(conversation);
            if (log.isDebugEnabled()) {
                log.debug("异步保存会话到数据库成功: conversationId={}, rows={}", conversationId, rows);
            }

            // 2. 更新缓存（集群共享）
            conversationDomainService.updateCache(conversation);
            if (log.isDebugEnabled()) {
                log.debug("异步更新缓存成功: conversationId={}", conversationId);
            }

            if (log.isInfoEnabled()) {
                log.info("异步会话持久化完成: conversationId={}", conversationId);
            }

        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("异步保存会话失败: conversationId={}", conversationId, e);
            }
            // 异步操作失败不影响用户体验，只记录日志
        }
    }

    /**
     * 异步保存消息到会话并更新缓存
     *
     * <p>
     * 用于流式对话中，在响应完成后异步保存消息
     * </p>
     *
     * @param conversation   会话实体
     * @param conversationId 会话ID
     */
    @Async("asyncExecutor")
    public void persistMessagesAsync(ConversationEntity conversation, String conversationId) {
        try {
            // 保存会话到数据库
            int rows = conversationMapper.save(conversation);
            if (log.isDebugEnabled()) {
                log.debug("异步保存消息到数据库成功: conversationId={}, rows={}", conversationId, rows);
            }

            // 更新缓存
            conversationDomainService.updateCache(conversation);
            if (log.isDebugEnabled()) {
                log.debug("异步更新缓存成功: conversationId={}", conversationId);
            }

        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("异步保存消息失败: conversationId={}", conversationId, e);
            }
        }
    }

    /**
     * 异步保存会话（流式对话专用）
     *
     * <p>
     * 包含消息保存、缓存更新。长期记忆由框架 Hook 自动管理。
     * </p>
     *
     * @param conversation   会话实体
     * @param conversationId 会话ID
     */
    @Async("asyncExecutor")
    public void persistConversationAndExtractMemoryAsync(
            ConversationEntity conversation,
            String conversationId) {
        try {
            // 1. 保存会话到数据库
            int rows = conversationMapper.save(conversation);
            if (log.isDebugEnabled()) {
                log.debug("流式对话-异步保存会话成功: conversationId={}, rows={}", conversationId, rows);
            }

            // 2. 更新缓存
            conversationDomainService.updateCache(conversation);
            if (log.isDebugEnabled()) {
                log.debug("流式对话-异步更新缓存成功: conversationId={}", conversationId);
            }

            if (log.isInfoEnabled()) {
                log.info("流式对话-异步持久化完成: conversationId={}", conversationId);
            }

        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("流式对话-异步持久化失败: conversationId={}", conversationId, e);
            }
        }
    }
}
