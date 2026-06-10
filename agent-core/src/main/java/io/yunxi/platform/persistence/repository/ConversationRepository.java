package io.yunxi.platform.persistence.repository;

import java.util.List;
import java.util.Optional;

import io.yunxi.platform.shared.entity.ConversationEntity;

/**
 * 会话存储仓库接口
 *
 * <p>抽象会话存储层，支持多种存储实现的灵活切换。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface ConversationRepository {

    boolean save(ConversationEntity conversation);
    Optional<ConversationEntity> findById(String conversationId);
    List<ConversationEntity> findByUserId(String userId);
    List<ConversationEntity> findByAgentName(String agentName);
    boolean deleteById(String conversationId);
    boolean existsById(String conversationId);
    Optional<ConversationEntity> findByUserIdAndAgentName(String userId, String agentName);
    long countByUserId(String userId);
    long count();
    void deleteAll();
    String getStorageType();
}
