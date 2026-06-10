package io.yunxi.platform.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.extern.slf4j.Slf4j;

/**
 * 组合存储实现（缓存 + 数据库）
 * <p>使用装饰器模式，组合多个存储实现，支持缓存和数据库降级。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Repository
public class CompositeConversationRepository implements ConversationRepository {

    private final ConversationRepository cacheRepository;
    private final ConversationRepository databaseRepository;

    public CompositeConversationRepository(
            InMemoryConversationRepository cacheRepository,
            DatabaseConversationRepository databaseRepository) {
        this.cacheRepository = cacheRepository;
        this.databaseRepository = databaseRepository;
    }

    @Override
    public boolean save(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) return false;
        boolean cacheSuccess = false, dbSuccess = false;
        try { cacheSuccess = cacheRepository.save(conversation); } catch (Exception e) { log.warn("缓存保存失败", e); }
        try { dbSuccess = databaseRepository.save(conversation); } catch (Exception e) { log.error("数据库保存失败", e); }
        return cacheSuccess || dbSuccess;
    }

    @Override
    public Optional<ConversationEntity> findById(String conversationId) {
        if (conversationId == null) return Optional.empty();
        Optional<ConversationEntity> cached = cacheRepository.findById(conversationId);
        if (cached.isPresent()) return cached;
        Optional<ConversationEntity> fromDb = databaseRepository.findById(conversationId);
        fromDb.ifPresent(cacheRepository::save);
        return fromDb;
    }

    @Override
    public List<ConversationEntity> findByUserId(String userId) {
        try { return databaseRepository.findByUserId(userId); }
        catch (Exception e) { log.error("数据库查询失败，降级到缓存", e); return cacheRepository.findByUserId(userId); }
    }

    @Override
    public List<ConversationEntity> findByAgentName(String agentName) {
        try { return databaseRepository.findByAgentName(agentName); }
        catch (Exception e) { log.error("数据库查询失败，降级到缓存", e); return cacheRepository.findByAgentName(agentName); }
    }

    @Override
    public Optional<ConversationEntity> findByUserIdAndAgentName(String userId, String agentName) {
        try { return databaseRepository.findByUserIdAndAgentName(userId, agentName); }
        catch (Exception e) { return cacheRepository.findByUserIdAndAgentName(userId, agentName); }
    }

    @Override
    public boolean deleteById(String conversationId) {
        boolean cacheDeleted = cacheRepository.deleteById(conversationId);
        boolean dbDeleted = databaseRepository.deleteById(conversationId);
        return cacheDeleted || dbDeleted;
    }

    @Override public boolean existsById(String conversationId) { return cacheRepository.existsById(conversationId) || databaseRepository.existsById(conversationId); }
    @Override public long countByUserId(String userId) { try { return databaseRepository.countByUserId(userId); } catch (Exception e) { return cacheRepository.countByUserId(userId); } }
    @Override public long count() { try { return databaseRepository.count(); } catch (Exception e) { return cacheRepository.count(); } }
    @Override public void deleteAll() { cacheRepository.deleteAll(); }
    @Override public String getStorageType() { return "composite(cache+database)"; }
}
