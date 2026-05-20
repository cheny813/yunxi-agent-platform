package io.yunxi.platform.infra.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import io.yunxi.platform.shared.entity.ConversationEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存存储实现
 * 
 * <p>
 * 使用 ConcurrentHashMap 存储会话，适合：
 * <ul>
 *   <li>开发测试环境</li>
 *   <li>单机部署场景</li>
 *   <li>临时会话存储</li>
 * </ul>
 * </p>
 * 
 * <p>
 * <b>注意</b>：服务重启后会话将丢失
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Repository
public class InMemoryConversationRepository implements ConversationRepository {

    /** 内存存储映射 */
    private final Map<String, ConversationEntity> storage = new ConcurrentHashMap<>();

    /**
     * 保存会话到内存
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
        storage.put(conversation.getId(), conversation);
        log.debug("内存存储保存会话: id={}, messageCount={}", 
                conversation.getId(), 
                conversation.getMessages() != null ? conversation.getMessages().size() : 0);
        return true;
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
        ConversationEntity entity = storage.get(conversationId);
        log.debug("内存存储查询会话: id={}, found={}", conversationId, entity != null);
        return Optional.ofNullable(entity);
    }

    /**
     * 根据用户 ID 查找所有会话（按更新时间倒序）
     *
     * @param userId 用户 ID
     * @return 会话列表
     */
    @Override
    public List<ConversationEntity> findByUserId(String userId) {
        if (userId == null) {
            return List.of();
        }
        List<ConversationEntity> results = storage.values().stream()
                .filter(conv -> userId.equals(conv.getUserId()))
                .sorted((a, b) -> {
                    if (a.getLastUpdatedAt() == null) return 1;
                    if (b.getLastUpdatedAt() == null) return -1;
                    return b.getLastUpdatedAt().compareTo(a.getLastUpdatedAt());
                })
                .collect(Collectors.toList());
        log.debug("内存存储查询用户会话: userId={}, count={}", userId, results.size());
        return results;
    }

    /**
     * 根据 Agent 名称查找所有会话（按更新时间倒序）
     *
     * @param agentName Agent 名称
     * @return 会话列表
     */
    @Override
    public List<ConversationEntity> findByAgentName(String agentName) {
        if (agentName == null) {
            return List.of();
        }
        return storage.values().stream()
                .filter(conv -> agentName.equals(conv.getAgentName()))
                .sorted((a, b) -> {
                    if (a.getLastUpdatedAt() == null) return 1;
                    if (b.getLastUpdatedAt() == null) return -1;
                    return b.getLastUpdatedAt().compareTo(a.getLastUpdatedAt());
                })
                .collect(Collectors.toList());
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
        ConversationEntity removed = storage.remove(conversationId);
        log.debug("内存存储删除会话: id={}, success={}", conversationId, removed != null);
        return removed != null;
    }

    /**
     * 检查会话是否存在
     *
     * @param conversationId 会话 ID
     * @return 存在返回 true
     */
    @Override
    public boolean existsById(String conversationId) {
        return conversationId != null && storage.containsKey(conversationId);
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
        return storage.values().stream()
                .filter(conv -> userId.equals(conv.getUserId()))
                .count();
    }

    /**
     * 统计所有会话数量
     *
     * @return 会话总数量
     */
    @Override
    public long count() {
        return storage.size();
    }

    /**
     * 清空所有会话
     */
    @Override
    public void deleteAll() {
        int size = storage.size();
        storage.clear();
        log.warn("内存存储清空所有会话: count={}", size);
    }

    /**
     * 获取存储类型
     *
     * @return 存储类型 "memory"
     */
    @Override
    public String getStorageType() {
        return "memory";
    }
}
