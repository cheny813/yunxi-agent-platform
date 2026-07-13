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
 * @version 2.0.0
 */
@Slf4j
@Repository
public class DatabaseConversationRepository implements ConversationRepository {

    private final ConversationMapper conversationMapper;

    /**
     * 构造数据库会话存储仓库。
     *
     * @param conversationMapper 会话数据访问映射器（MyBatis）
     */
    public DatabaseConversationRepository(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    /**
     * 保存会话到 MySQL（按影响行数判断成功）。
     *
     * @param conversation 会话实体（id 为 null 时直接失败）
     * @return 影响行数大于 0 返回 true
     */
    @Override
    public boolean save(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) { log.warn("保存失败：会话或会话ID为空"); return false; }
        try { return conversationMapper.save(conversation) > 0; }
        catch (Exception e) { log.error("数据库存储保存会话失败", e); return false; }
    }

    /**
     * 按 ID 从数据库查询会话。
     *
     * @param conversationId 会话标识
     * @return 会话实体 Optional
     */
    @Override
    public Optional<ConversationEntity> findById(String conversationId) {
        if (conversationId == null) return Optional.empty();
        try { return Optional.ofNullable(conversationMapper.findById(conversationId)); }
        catch (Exception e) { log.error("数据库存储查询会话失败", e); return Optional.empty(); }
    }

    /**
     * 按用户 ID 从数据库查询会话。
     *
     * @param userId 用户标识
     * @return 会话列表（异常或为空时返回空列表）
     */
    @Override
    public List<ConversationEntity> findByUserId(String userId) {
        if (userId == null) return List.of();
        try { List<ConversationEntity> r = conversationMapper.findByUserId(userId); return r != null ? r : List.of(); }
        catch (Exception e) { log.error("数据库存储查询用户会话失败", e); return List.of(); }
    }

    /**
     * 按 Agent 名称从数据库查询会话。
     *
     * @param agentName Agent 名称
     * @return 会话列表（异常或为空时返回空列表）
     */
    @Override
    public List<ConversationEntity> findByAgentName(String agentName) {
        if (agentName == null) return List.of();
        try { List<ConversationEntity> r = conversationMapper.findByAgentName(agentName); return r != null ? r : List.of(); }
        catch (Exception e) { log.error("数据库存储查询Agent会话失败", e); return List.of(); }
    }

    /**
     * 按用户 ID 与 Agent 名称查询唯一会话（取结果首条）。
     *
     * @param userId 用户标识
     * @param agentName Agent 名称
     * @return 命中的会话实体 Optional
     */
    @Override
    public Optional<ConversationEntity> findByUserIdAndAgentName(String userId, String agentName) {
        if (userId == null || agentName == null) return Optional.empty();
        try {
            List<ConversationEntity> results = conversationMapper.findByUserIdAndAgentName(userId, agentName);
            return results != null && !results.isEmpty() ? Optional.of(results.get(0)) : Optional.empty();
        } catch (Exception e) { log.error("数据库存储查询会话失败", e); return Optional.empty(); }
    }

    /**
     * 按 ID 从数据库删除会话。
     *
     * @param conversationId 会话标识
     * @return 删除操作成功返回 true，异常返回 false
     */
    @Override public boolean deleteById(String conversationId) {
        if (conversationId == null) return false;
        try { conversationMapper.deleteById(conversationId); return true; }
        catch (Exception e) { log.error("数据库存储删除会话失败", e); return false; }
    }
    /**
     * 判断会话是否存在（委托 findById）。
     *
     * @param conversationId 会话标识
     * @return 存在返回 true
     */
    @Override public boolean existsById(String conversationId) { return findById(conversationId).isPresent(); }
    /**
     * 统计用户会话数量（空结果按 0 计）。
     *
     * @param userId 用户标识
     * @return 会话数量
     */
    @Override public long countByUserId(String userId) { try { Long c = conversationMapper.countByUserId(userId); return c != null ? c : 0; } catch (Exception e) { return 0; } }
    /**
     * 统计全部会话数量（空结果按 0 计）。
     *
     * @return 会话总数
     */
    @Override public long count() { try { Long c = conversationMapper.count(); return c != null ? c : 0; } catch (Exception e) { return 0; } }
    /**
     * 清空操作：数据库实现不支持，仅记录告警。
     */
    @Override public void deleteAll() { log.warn("数据库存储不支持清空所有会话操作"); }
    /**
     * 返回存储类型标识。
     *
     * @return {@code "database"}
     */
    @Override public String getStorageType() { return "database"; }
}
