package io.yunxi.platform.persistence.repository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.extern.slf4j.Slf4j;

/**
 * 内存存储实现
 * <p>使用 ConcurrentHashMap 存储会话，适合开发测试环境。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Repository
public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<String, ConversationEntity> storage = new ConcurrentHashMap<>();

    /**
     * 将会话存入内存 Map（按 id 覆盖写入）。
     *
     * @param conversation 会话实体（id 为 null 时直接失败）
     * @return 保存成功返回 true
     */
    @Override
    public boolean save(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) { log.warn("保存失败：会话或会话ID为空"); return false; }
        storage.put(conversation.getId(), conversation);
        return true;
    }

    /**
     * 按 ID 从内存查询会话。
     *
     * @param conversationId 会话标识
     * @return 会话实体 Optional
     */
    @Override
    public Optional<ConversationEntity> findById(String conversationId) {
        return conversationId == null ? Optional.empty() : Optional.ofNullable(storage.get(conversationId));
    }

    /**
     * 按用户 ID 查询会话，按最近更新时间倒序排列。
     *
     * @param userId 用户标识
     * @return 会话列表
     */
    @Override
    public List<ConversationEntity> findByUserId(String userId) {
        if (userId == null) return List.of();
        return storage.values().stream()
                .filter(conv -> userId.equals(conv.getUserId()))
                .sorted((a, b) -> {
                    if (a.getLastUpdatedAt() == null) return 1;
                    if (b.getLastUpdatedAt() == null) return -1;
                    return b.getLastUpdatedAt().compareTo(a.getLastUpdatedAt());
                }).collect(Collectors.toList());
    }

    /**
     * 按 Agent 名称查询会话，按最近更新时间倒序排列。
     *
     * @param agentName Agent 名称
     * @return 会话列表
     */
    @Override
    public List<ConversationEntity> findByAgentName(String agentName) {
        if (agentName == null) return List.of();
        return storage.values().stream()
                .filter(conv -> agentName.equals(conv.getAgentName()))
                .sorted((a, b) -> {
                    if (a.getLastUpdatedAt() == null) return 1;
                    if (b.getLastUpdatedAt() == null) return -1;
                    return b.getLastUpdatedAt().compareTo(a.getLastUpdatedAt());
                }).collect(Collectors.toList());
    }

    /**
     * 按用户 ID 与 Agent 名称查询会话，过滤已过期项并取最近更新的一条。
     *
     * @param userId 用户标识
     * @param agentName Agent 名称
     * @return 命中的会话实体 Optional
     */
    @Override
    public Optional<ConversationEntity> findByUserIdAndAgentName(String userId, String agentName) {
        if (userId == null || agentName == null) return Optional.empty();
        return storage.values().stream()
                .filter(conv -> userId.equals(conv.getUserId()) && agentName.equals(conv.getAgentName()))
                .filter(conv -> conv.getExpiresAt() == null || conv.getExpiresAt().isAfter(LocalDateTime.now()))
                .max(Comparator.comparing(ConversationEntity::getLastUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /**
     * 按 ID 从内存移除会话。
     *
     * @param conversationId 会话标识
     * @return 移除成功返回 true
     */
    @Override public boolean deleteById(String conversationId) { return conversationId != null && storage.remove(conversationId) != null; }
    /**
     * 判断会话是否存在于内存。
     *
     * @param conversationId 会话标识
     * @return 存在返回 true
     */
    @Override public boolean existsById(String conversationId) { return conversationId != null && storage.containsKey(conversationId); }
    /**
     * 统计用户会话数量。
     *
     * @param userId 用户标识
     * @return 会话数量
     */
    @Override public long countByUserId(String userId) { return userId == null ? 0 : storage.values().stream().filter(conv -> userId.equals(conv.getUserId())).count(); }
    /**
     * 统计内存中会话总数。
     *
     * @return 会话总数
     */
    @Override public long count() { return storage.size(); }
    /**
     * 清空内存中所有会话。
     */
    @Override public void deleteAll() { storage.clear(); }
    /**
     * 返回存储类型标识。
     *
     * @return {@code "memory"}
     */
    @Override public String getStorageType() { return "memory"; }
}
