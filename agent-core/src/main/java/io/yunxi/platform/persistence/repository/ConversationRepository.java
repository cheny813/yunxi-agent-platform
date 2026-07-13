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
 * @version 2.0.0
 */
public interface ConversationRepository {

    /**
     * 保存（新增或更新）会话。
     *
     * @param conversation 会话实体
     * @return 保存成功返回 true，否则返回 false
     */
    boolean save(ConversationEntity conversation);

    /**
     * 按会话 ID 查询会话。
     *
     * @param conversationId 会话标识
     * @return 会话实体 Optional，不存在时为空
     */
    Optional<ConversationEntity> findById(String conversationId);

    /**
     * 按用户 ID 查询其全部会话。
     *
     * @param userId 用户标识
     * @return 会话列表，用户为空时返回空列表
     */
    List<ConversationEntity> findByUserId(String userId);

    /**
     * 按 Agent 名称查询会话。
     *
     * @param agentName Agent 名称
     * @return 会话列表，未提供名称时返回空列表
     */
    List<ConversationEntity> findByAgentName(String agentName);

    /**
     * 按会话 ID 删除会话。
     *
     * @param conversationId 会话标识
     * @return 删除成功返回 true，否则返回 false
     */
    boolean deleteById(String conversationId);

    /**
     * 判断会话是否存在。
     *
     * @param conversationId 会话标识
     * @return 存在返回 true
     */
    boolean existsById(String conversationId);

    /**
     * 按用户 ID 与 Agent 名称查询唯一会话。
     *
     * @param userId 用户标识
     * @param agentName Agent 名称
     * @return 命中的会话实体 Optional
     */
    Optional<ConversationEntity> findByUserIdAndAgentName(String userId, String agentName);

    /**
     * 统计指定用户的会话数量。
     *
     * @param userId 用户标识
     * @return 会话数量
     */
    long countByUserId(String userId);

    /**
     * 统计全部会话数量。
     *
     * @return 会话总数
     */
    long count();

    /**
     * 清空全部会话（部分实现可能不支持）。
     */
    void deleteAll();

    /**
     * 返回底层存储类型标识。
     *
     * @return 存储类型字符串，如 {@code "database"} 或 {@code "memory"}
     */
    String getStorageType();
}
