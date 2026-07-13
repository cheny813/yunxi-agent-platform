package io.yunxi.platform.persistence;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.mapper.ConversationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据库持久化策略
 *
 * <p>
 * 将数据持久化到 MySQL 数据库，适合全量数据存储、持久化保存、复杂查询。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service("databasePersistenceStrategy")
public class DatabasePersistenceStrategy implements DataPersistenceStrategy {

    private final ConversationMapper conversationMapper;

    public DatabasePersistenceStrategy(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    /**
     * 将会话实体持久化到数据库。
     *
     * @param conversation 会话实体（id 为 null 时直接返回 false）
     * @return 受影响行数大于 0 时返回 true
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null)
            return false;
        try {
            int rows = conversationMapper.save(conversation);
            log.debug("数据库保存会话: id={}, rows={}", conversation.getId(), rows);
            return rows > 0;
        } catch (Exception e) {
            log.error("数据库保存会话失败: id={}, error={}", conversation.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 按会话 ID 从数据库删除会话。
     *
     * @param conversationId 会话标识
     * @return 删除操作成功返回 true，异常时返回 false
     */
    @Override
    public boolean deleteConversation(String conversationId) {
        try {
            conversationMapper.deleteById(conversationId);
            log.debug("数据库删除会话: id={}", conversationId);
            return true;
        } catch (Exception e) {
            log.error("数据库删除会话失败: id={}, error={}", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * 保存会话记忆（当前实现为无操作占位，记忆由会话实体统一管理）。
     *
     * @param conversationId 会话标识
     * @param messages 消息列表
     * @param config 记忆配置
     * @return 恒为 true
     */
    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        log.debug("数据库保存记忆: conversationId={}, count={}", conversationId, messages.size());
        return true;
    }

    /**
     * 从数据库获取会话记忆，并按配置裁剪上下文长度。
     *
     * @param conversationId 会话标识
     * @param config 记忆配置（提供最大上下文大小）
     * @return 消息列表，超过上限时仅返回末尾 maxContextSize 条；异常或未命中时返回空列表
     */
    @Override
    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        try {
            ConversationEntity entity = conversationMapper.findById(conversationId);
            if (entity != null && entity.getMessages() != null) {
                int maxSize = config.getMaxContextSize() > 0 ? config.getMaxContextSize() : Integer.MAX_VALUE;
                List<Msg> allMessages = entity.getMessages();
                if (allMessages.size() > maxSize)
                    return allMessages.subList(allMessages.size() - maxSize, allMessages.size());
                return allMessages;
            }
        } catch (Exception e) {
            log.error("数据库获取记忆失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
        return List.of();
    }

    /**
     * 删除指定会话的记忆（等价于删除会话本身）。
     *
     * @param conversationId 会话标识
     * @return 删除结果
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        return deleteConversation(conversationId);
    }

    /**
     * 返回策略名称。
     *
     * @return 固定字符串 {@code "Database"}
     */
    @Override
    public String getStrategyName() {
        return "Database";
    }

    /**
     * 返回策略类型。
     *
     * @return 主策略 {@link StrategyType#PRIMARY}
     */
    @Override
    public StrategyType getStrategyType() {
        return StrategyType.PRIMARY;
    }
}
