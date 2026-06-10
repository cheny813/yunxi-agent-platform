package io.yunxi.platform.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.mapper.ConversationMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据库存储实现
 * <p>使用 MyBatis 存储会话到 MySQL 数据库。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Repository
public class DatabaseConversationRepository implements ConversationRepository {

    private final ConversationMapper conversationMapper;

    public DatabaseConversationRepository(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    @Override
    public boolean save(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) { log.warn("保存失败：会话或会话ID为空"); return false; }
        try { return conversationMapper.save(conversation) > 0; }
        catch (Exception e) { log.error("数据库存储保存会话失败", e); return false; }
    }

    @Override
    public Optional<ConversationEntity> findById(String conversationId) {
        if (conversationId == null) return Optional.empty();
        try { return Optional.ofNullable(conversationMapper.findById(conversationId)); }
        catch (Exception e) { log.error("数据库存储查询会话失败", e); return Optional.empty(); }
    }

    @Override
    public List<ConversationEntity> findByUserId(String userId) {
        if (userId == null) return List.of();
        try { List<ConversationEntity> r = conversationMapper.findByUserId(userId); return r != null ? r : List.of(); }
        catch (Exception e) { log.error("数据库存储查询用户会话失败", e); return List.of(); }
    }

    @Override
    public List<ConversationEntity> findByAgentName(String agentName) {
        if (agentName == null) return List.of();
        try { List<ConversationEntity> r = conversationMapper.findByAgentName(agentName); return r != null ? r : List.of(); }
        catch (Exception e) { log.error("数据库存储查询Agent会话失败", e); return List.of(); }
    }

    @Override
    public Optional<ConversationEntity> findByUserIdAndAgentName(String userId, String agentName) {
        if (userId == null || agentName == null) return Optional.empty();
        try {
            List<ConversationEntity> results = conversationMapper.findByUserIdAndAgentName(userId, agentName);
            return results != null && !results.isEmpty() ? Optional.of(results.get(0)) : Optional.empty();
        } catch (Exception e) { log.error("数据库存储查询会话失败", e); return Optional.empty(); }
    }

    @Override public boolean deleteById(String conversationId) {
        if (conversationId == null) return false;
        try { conversationMapper.deleteById(conversationId); return true; }
        catch (Exception e) { log.error("数据库存储删除会话失败", e); return false; }
    }
    @Override public boolean existsById(String conversationId) { return findById(conversationId).isPresent(); }
    @Override public long countByUserId(String userId) { try { Long c = conversationMapper.countByUserId(userId); return c != null ? c : 0; } catch (Exception e) { return 0; } }
    @Override public long count() { try { Long c = conversationMapper.count(); return c != null ? c : 0; } catch (Exception e) { return 0; } }
    @Override public void deleteAll() { log.warn("数据库存储不支持清空所有会话操作"); }
    @Override public String getStorageType() { return "database"; }
}
