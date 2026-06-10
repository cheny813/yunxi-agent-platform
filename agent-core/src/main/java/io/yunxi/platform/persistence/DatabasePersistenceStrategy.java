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
 * @version 1.0.0
 */
@Slf4j
@Service("databasePersistenceStrategy")
public class DatabasePersistenceStrategy implements DataPersistenceStrategy {

    private final ConversationMapper conversationMapper;

    public DatabasePersistenceStrategy(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

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

    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        log.debug("数据库保存记忆: conversationId={}, count={}", conversationId, messages.size());
        return true;
    }

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

    @Override
    public boolean deleteMemory(String conversationId) {
        return deleteConversation(conversationId);
    }

    @Override
    public String getStrategyName() {
        return "Database";
    }

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.PRIMARY;
    }
}
