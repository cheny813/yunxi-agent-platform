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
 * @version 1.0.0
 */
@Slf4j
@Repository
public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<String, ConversationEntity> storage = new ConcurrentHashMap<>();

    @Override
    public boolean save(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) { log.warn("保存失败：会话或会话ID为空"); return false; }
        storage.put(conversation.getId(), conversation);
        return true;
    }

    @Override
    public Optional<ConversationEntity> findById(String conversationId) {
        return conversationId == null ? Optional.empty() : Optional.ofNullable(storage.get(conversationId));
    }

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

    @Override
    public Optional<ConversationEntity> findByUserIdAndAgentName(String userId, String agentName) {
        if (userId == null || agentName == null) return Optional.empty();
        return storage.values().stream()
                .filter(conv -> userId.equals(conv.getUserId()) && agentName.equals(conv.getAgentName()))
                .filter(conv -> conv.getExpiresAt() == null || conv.getExpiresAt().isAfter(LocalDateTime.now()))
                .max(Comparator.comparing(ConversationEntity::getLastUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override public boolean deleteById(String conversationId) { return conversationId != null && storage.remove(conversationId) != null; }
    @Override public boolean existsById(String conversationId) { return conversationId != null && storage.containsKey(conversationId); }
    @Override public long countByUserId(String userId) { return userId == null ? 0 : storage.values().stream().filter(conv -> userId.equals(conv.getUserId())).count(); }
    @Override public long count() { return storage.size(); }
    @Override public void deleteAll() { storage.clear(); }
    @Override public String getStorageType() { return "memory"; }
}
