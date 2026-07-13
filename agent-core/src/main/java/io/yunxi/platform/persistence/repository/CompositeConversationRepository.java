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
 * @version 2.0.0
 */
@Slf4j
@Repository
public class CompositeConversationRepository implements ConversationRepository {

    private final ConversationRepository cacheRepository;
    private final ConversationRepository databaseRepository;

    /**
     * 构造组合存储仓库（装饰器模式）。
     *
     * @param cacheRepository     内存缓存仓库（优先读取、降级来源）
     * @param databaseRepository  数据库仓库（主存储、查询主来源）
     */
    public CompositeConversationRepository(
            InMemoryConversationRepository cacheRepository,
            DatabaseConversationRepository databaseRepository) {
        this.cacheRepository = cacheRepository;
        this.databaseRepository = databaseRepository;
    }

    /**
     * 保存会话（先写缓存再写数据库，任一成功即视为成功）。
     *
     * @param conversation 待保存的会话实体
     * @return 缓存或数据库任一写入成功返回 true
     */
    @Override
    public boolean save(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) return false;
        boolean cacheSuccess = false, dbSuccess = false;
        try { cacheSuccess = cacheRepository.save(conversation); } catch (Exception e) { log.warn("缓存保存失败", e); }
        try { dbSuccess = databaseRepository.save(conversation); } catch (Exception e) { log.error("数据库保存失败", e); }
        return cacheSuccess || dbSuccess;
    }

    /**
     * 按 ID 查询会话（先查缓存，未命中再查数据库并回填缓存）。
     *
     * @param conversationId 会话 ID
     * @return 命中的会话实体；不存在时返回空 Optional
     */
    @Override
    public Optional<ConversationEntity> findById(String conversationId) {
        if (conversationId == null) return Optional.empty();
        Optional<ConversationEntity> cached = cacheRepository.findById(conversationId);
        if (cached.isPresent()) return cached;
        Optional<ConversationEntity> fromDb = databaseRepository.findById(conversationId);
        fromDb.ifPresent(cacheRepository::save);
        return fromDb;
    }

    /**
     * 按用户 ID 查询会话列表（数据库优先，异常时降级到缓存）。
     *
     * @param userId 用户 ID
     * @return 该用户的会话实体列表
     */
    @Override
    public List<ConversationEntity> findByUserId(String userId) {
        try { return databaseRepository.findByUserId(userId); }
        catch (Exception e) { log.error("数据库查询失败，降级到缓存", e); return cacheRepository.findByUserId(userId); }
    }

    /**
     * 按 Agent 名称查询会话列表（数据库优先，异常时降级到缓存）。
     *
     * @param agentName Agent 名称
     * @return 该 Agent 的会话实体列表
     */
    @Override
    public List<ConversationEntity> findByAgentName(String agentName) {
        try { return databaseRepository.findByAgentName(agentName); }
        catch (Exception e) { log.error("数据库查询失败，降级到缓存", e); return cacheRepository.findByAgentName(agentName); }
    }

    /**
     * 按用户 ID 与 Agent 名称联合查询会话（数据库优先，异常时降级）。
     *
     * @param userId    用户 ID
     * @param agentName Agent 名称
     * @return 命中的会话实体；不存在时返回空 Optional
     */
    @Override
    public Optional<ConversationEntity> findByUserIdAndAgentName(String userId, String agentName) {
        try { return databaseRepository.findByUserIdAndAgentName(userId, agentName); }
        catch (Exception e) { return cacheRepository.findByUserIdAndAgentName(userId, agentName); }
    }

    /**
     * 删除会话（缓存与数据库均尝试删除，任一成功即视为成功）。
     *
     * @param conversationId 会话 ID
     * @return 缓存或数据库任一删除成功返回 true
     */
    @Override
    public boolean deleteById(String conversationId) {
        boolean cacheDeleted = cacheRepository.deleteById(conversationId);
        boolean dbDeleted = databaseRepository.deleteById(conversationId);
        return cacheDeleted || dbDeleted;
    }

    /**
     * 判断会话是否存在（缓存或数据库任一存在即返回 true）。
     *
     * @param conversationId 会话 ID
     * @return 存在返回 true
     */
    @Override public boolean existsById(String conversationId) { return cacheRepository.existsById(conversationId) || databaseRepository.existsById(conversationId); }

    /**
     * 统计指定用户的会话数量（数据库优先，异常时降级到缓存）。
     *
     * @param userId 用户 ID
     * @return 会话数量
     */
    @Override public long countByUserId(String userId) { try { return databaseRepository.countByUserId(userId); } catch (Exception e) { return cacheRepository.countByUserId(userId); } }

    /**
     * 统计全部会话数量（数据库优先，异常时降级到缓存）。
     *
     * @return 会话总数
     */
    @Override public long count() { try { return databaseRepository.count(); } catch (Exception e) { return cacheRepository.count(); } }

    /**
     * 清空全部会话（仅清空缓存，数据库作为主存储保留）。
     */
    @Override public void deleteAll() { cacheRepository.deleteAll(); }

    /**
     * 返回存储类型标识。
     *
     * @return 固定返回 "composite(cache+database)"
     */
    @Override public String getStorageType() { return "composite(cache+database)"; }
}
