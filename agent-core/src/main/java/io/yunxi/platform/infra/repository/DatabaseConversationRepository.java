package io.yunxi.platform.infra.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.mapper.ConversationMapper;

import java.util.List;
import java.util.Optional;

/**
 * 数据库存储实现
 * 
 * <p>
 * 使用 MyBatis 存储会话到 MySQL 数据库，适合：
 * <ul>
 *   <li>生产环境</li>
 *   <li>需要持久化的场景</li>
 *   <li>集群部署场景</li>
 * </ul>
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Repository
public class DatabaseConversationRepository implements ConversationRepository {

    /** 会话 Mapper */
    private final ConversationMapper conversationMapper;

    /**
     * 构造数据库存储
     *
     * @param conversationMapper 会话 Mapper
     */
    public DatabaseConversationRepository(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    /**
     * 保存会话到数据库
     *
     * @param conversation 会话实体
     * @return 保存成功返回 true
     */
    @Override
    public boolean save(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) {
            log.warn("保存失败：会话或会话ID为空");
            return false;
        }
        try {
            int rows = conversationMapper.save(conversation);
            log.debug("数据库存储保存会话: id={}, rows={}, messageCount={}", 
                    conversation.getId(), 
                    rows,
                    conversation.getMessages() != null ? conversation.getMessages().size() : 0);
            return rows > 0;
        } catch (Exception e) {
            log.error("数据库存储保存会话失败: id={}, error={}", conversation.getId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 根据 ID 查找会话
     *
     * @param conversationId 会话 ID
     * @return 会话实体
     */
    @Override
    public Optional<ConversationEntity> findById(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        try {
            ConversationEntity entity = conversationMapper.findById(conversationId);
            log.debug("数据库存储查询会话: id={}, found={}", conversationId, entity != null);
            return Optional.ofNullable(entity);
        } catch (Exception e) {
            log.error("数据库存储查询会话失败: id={}, error={}", conversationId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 根据用户 ID 查找所有会话
     *
     * @param userId 用户 ID
     * @return 会话列表
     */
    @Override
    public List<ConversationEntity> findByUserId(String userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            List<ConversationEntity> results = conversationMapper.findByUserId(userId);
            log.debug("数据库存储查询用户会话: userId={}, count={}", userId, results != null ? results.size() : 0);
            return results != null ? results : List.of();
        } catch (Exception e) {
            log.error("数据库存储查询用户会话失败: userId={}, error={}", userId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 根据 Agent 名称查找所有会话
     *
     * @param agentName Agent 名称
     * @return 会话列表
     */
    @Override
    public List<ConversationEntity> findByAgentName(String agentName) {
        if (agentName == null) {
            return List.of();
        }
        try {
            List<ConversationEntity> results = conversationMapper.findByAgentName(agentName);
            return results != null ? results : List.of();
        } catch (Exception e) {
            log.error("数据库存储查询Agent会话失败: agentName={}, error={}", agentName, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 删除会话
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    @Override
    public boolean deleteById(String conversationId) {
        if (conversationId == null) {
            return false;
        }
        try {
            conversationMapper.deleteById(conversationId);
            log.debug("数据库存储删除会话: id={}", conversationId);
            return true;
        } catch (Exception e) {
            log.error("数据库存储删除会话失败: id={}, error={}", conversationId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 检查会话是否存在
     *
     * @param conversationId 会话 ID
     * @return 存在返回 true
     */
    @Override
    public boolean existsById(String conversationId) {
        return findById(conversationId).isPresent();
    }

    /**
     * 统计用户的会话数量
     *
     * @param userId 用户 ID
     * @return 会话数量
     */
    @Override
    public long countByUserId(String userId) {
        if (userId == null) {
            return 0;
        }
        try {
            Long count = conversationMapper.countByUserId(userId);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("数据库存储统计用户会话失败: userId={}, error={}", userId, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 统计所有会话数量
     *
     * @return 会话总数量
     */
    @Override
    public long count() {
        try {
            Long count = conversationMapper.count();
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("数据库存储统计会话失败: error={}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 清空所有会话（数据库存储不支持此操作）
     */
    @Override
    public void deleteAll() {
        log.warn("数据库存储不支持清空所有会话操作");
    }

    /**
     * 获取存储类型
     *
     * @return 存储类型 "database"
     */
    @Override
    public String getStorageType() {
        return "database";
    }
}
