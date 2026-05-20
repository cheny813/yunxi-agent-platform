package io.yunxi.platform.infra.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import io.yunxi.platform.shared.entity.ConversationEntity;

import java.util.List;
import java.util.Optional;

/**
 * 组合存储实现（缓存 + 数据库）
 *
 * <p>
 * 使用装饰器模式，组合多个存储实现，支持：
 * <ul>
 * <li>读取：优先从缓存读取，缓存未命中则从数据库读取并回填缓存</li>
 * <li>写入：同时写入缓存和数据库</li>
 * <li>降级：数据库故障时自动降级到缓存模式</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Repository
public class CompositeConversationRepository implements ConversationRepository {

    /** 缓存存储 */
    private final ConversationRepository cacheRepository;
    /** 数据库存储 */
    private final ConversationRepository databaseRepository;

    /**
     * 构造组合存储
     *
     * @param cacheRepository    缓存存储实现
     * @param databaseRepository 数据库存储实现
     */
    public CompositeConversationRepository(
            InMemoryConversationRepository cacheRepository,
            DatabaseConversationRepository databaseRepository) {
        this.cacheRepository = cacheRepository;
        this.databaseRepository = databaseRepository;
        log.info("组合存储初始化完成: cache={}, database={}",
                cacheRepository.getStorageType(),
                databaseRepository.getStorageType());
    }

    /**
     * 保存会话（同时写入缓存和数据库，任一成功即返回成功）
     *
     * @param conversation 会话实体
     * @return 任一存储保存成功返回 true
     */
    @Override
    public boolean save(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) {
            log.warn("保存失败：会话或会话ID为空");
            return false;
        }

        boolean cacheSuccess = false;
        boolean dbSuccess = false;

        // 1. 先保存到缓存
        try {
            cacheSuccess = cacheRepository.save(conversation);
        } catch (Exception e) {
            log.warn("缓存保存失败: id={}, error={}", conversation.getId(), e.getMessage());
        }

        // 2. 再保存到数据库
        try {
            dbSuccess = databaseRepository.save(conversation);
        } catch (Exception e) {
            log.error("数据库保存失败: id={}, error={}", conversation.getId(), e.getMessage());
            // 数据库失败不影响缓存模式继续工作
        }

        // 只要有一个成功就算成功
        boolean success = cacheSuccess || dbSuccess;
        log.debug("组合存储保存会话: id={}, cache={}, db={}, success={}",
                conversation.getId(), cacheSuccess, dbSuccess, success);
        return success;
    }

    /**
     * 根据 ID 查找会话（优先查缓存，未命中则查数据库并回填缓存）
     *
     * @param conversationId 会话 ID
     * @return 会话实体
     */
    @Override
    public Optional<ConversationEntity> findById(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }

        // 1. 优先从缓存读取
        Optional<ConversationEntity> cached = cacheRepository.findById(conversationId);
        if (cached.isPresent()) {
            log.debug("组合存储从缓存命中: id={}", conversationId);
            return cached;
        }

        // 2. 缓存未命中，从数据库读取
        Optional<ConversationEntity> fromDb = databaseRepository.findById(conversationId);
        if (fromDb.isPresent()) {
            log.debug("组合存储从数据库命中，回填缓存: id={}", conversationId);
            // 回填缓存
            cacheRepository.save(fromDb.get());
        } else {
            log.debug("组合存储未找到会话: id={}", conversationId);
        }

        return fromDb;
    }

    /**
     * 根据用户 ID 查找会话（优先查数据库，失败降级到缓存）
     *
     * @param userId 用户 ID
     * @return 会话列表
     */
    @Override
    public List<ConversationEntity> findByUserId(String userId) {
        if (userId == null) {
            return List.of();
        }

        // 列表查询直接走数据库（保证数据完整性）
        try {
            return databaseRepository.findByUserId(userId);
        } catch (Exception e) {
            log.error("数据库查询用户会话失败，降级到缓存: userId={}, error={}", userId, e.getMessage());
            return cacheRepository.findByUserId(userId);
        }
    }

    /**
     * 根据 Agent 名称查找会话（优先查数据库，失败降级到缓存）
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
            return databaseRepository.findByAgentName(agentName);
        } catch (Exception e) {
            log.error("数据库查询Agent会话失败，降级到缓存: agentName={}, error={}", agentName, e.getMessage());
            return cacheRepository.findByAgentName(agentName);
        }
    }

    /**
     * 删除会话（同时从缓存和数据库删除，任一成功即返回成功）
     *
     * @param conversationId 会话 ID
     * @return 任一存储删除成功返回 true
     */
    @Override
    public boolean deleteById(String conversationId) {
        if (conversationId == null) {
            return false;
        }

        boolean cacheDeleted = cacheRepository.deleteById(conversationId);
        boolean dbDeleted = databaseRepository.deleteById(conversationId);

        log.debug("组合存储删除会话: id={}, cache={}, db={}",
                conversationId, cacheDeleted, dbDeleted);
        return cacheDeleted || dbDeleted;
    }

    /**
     * 检查会话是否存在（缓存或数据库任一存在即返回 true）
     *
     * @param conversationId 会话 ID
     * @return 存在返回 true
     */
    @Override
    public boolean existsById(String conversationId) {
        return cacheRepository.existsById(conversationId) || databaseRepository.existsById(conversationId);
    }

    /**
     * 统计用户的会话数量（优先查数据库，失败降级到缓存）
     *
     * @param userId 用户 ID
     * @return 会话数量
     */
    @Override
    public long countByUserId(String userId) {
        try {
            return databaseRepository.countByUserId(userId);
        } catch (Exception e) {
            return cacheRepository.countByUserId(userId);
        }
    }

    /**
     * 统计所有会话数量（优先查数据库，失败降级到缓存）
     *
     * @return 会话总数量
     */
    @Override
    public long count() {
        try {
            return databaseRepository.count();
        } catch (Exception e) {
            return cacheRepository.count();
        }
    }

    /**
     * 清空所有会话（仅清空缓存，数据库不清空）
     */
    @Override
    public void deleteAll() {
        cacheRepository.deleteAll();
        log.warn("组合存储清空缓存（数据库不清空）");
    }

    /**
     * 获取存储类型
     *
     * @return 存储类型 "composite(cache+database)"
     */
    @Override
    public String getStorageType() {
        return "composite(cache+database)";
    }
}
