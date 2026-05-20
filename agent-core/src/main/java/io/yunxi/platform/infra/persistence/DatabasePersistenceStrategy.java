package io.yunxi.platform.infra.persistence;

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
 * 将数据持久化到 MySQL 数据库，适合：
 * <ul>
 *   <li>全量数据存储</li>
 *   <li>持久化保存</li>
   <li>复杂查询</li>
 * </ul>
 * </p>
 * 
 * <p>
 * <b>特点</b>：
 * <ul>
 *   <li>支持事务</li>
 *   <li>数据一致性</li>
 *   <li>可作为主存储</li>
 * </ul>
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service("databasePersistenceStrategy")
public class DatabasePersistenceStrategy implements DataPersistenceStrategy {

    /** 会话 Mapper */
    private final ConversationMapper conversationMapper;

    /**
     * 构造数据库持久化策略
     *
     * @param conversationMapper 会话 Mapper
     */
    public DatabasePersistenceStrategy(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    /**
     * 保存会话到数据库
     *
     * @param conversation 会话实体
     * @return 保存成功返回 true
     */
    @Override
    public boolean saveConversation(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) {
            return false;
        }
        
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
     * 删除会话
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
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
     * 保存记忆（通过会话对象一起保存）
     *
     * @param conversationId 会话 ID
     * @param messages       消息列表
     * @param config         记忆配置
     * @return 保存成功返回 true
     */
    @Override
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        // 记忆数据已经通过 ConversationEntity 的 messages 字段一起保存到数据库
        log.debug("数据库保存记忆: conversationId={}, count={} (通过会话对象)", conversationId, messages.size());
        return true;
    }

    /**
     * 获取会话记忆
     *
     * @param conversationId 会话 ID
     * @param config         记忆配置
     * @return 消息列表
     */
    @Override
    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        // 从会话对象中获取记忆
        try {
            ConversationEntity entity = conversationMapper.findById(conversationId);
            if (entity != null && entity.getMessages() != null) {
                // 根据配置决定返回多少条
                int maxSize = config.getMaxContextSize() > 0 ? config.getMaxContextSize() : Integer.MAX_VALUE;
                List<Msg> allMessages = entity.getMessages();
                if (allMessages.size() > maxSize) {
                    return allMessages.subList(allMessages.size() - maxSize, allMessages.size());
                }
                return allMessages;
            }
        } catch (Exception e) {
            log.error("数据库获取记忆失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
        return List.of();
    }

    /**
     * 删除记忆（通过删除会话实现）
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    @Override
    public boolean deleteMemory(String conversationId) {
        // 记忆数据通过会话一起删除
        return deleteConversation(conversationId);
    }

    /**
     * 获取策略名称
     *
     * @return 策略名称 "Database"
     */
    @Override
    public String getStrategyName() {
        return "Database";
    }

    /**
     * 获取策略类型
     *
     * @return PRIMARY 主存储类型
     */
    @Override
    public StrategyType getStrategyType() {
        return StrategyType.PRIMARY;
    }
}

