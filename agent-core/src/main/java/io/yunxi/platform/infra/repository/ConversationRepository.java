package io.yunxi.platform.infra.repository;

import java.util.List;
import java.util.Optional;

import io.yunxi.platform.shared.entity.ConversationEntity;

/**
 * 会话存储仓库接口
 * 
 * <p>
 * 抽象会话存储层，支持多种存储实现的灵活切换：
 * <ul>
 *   <li>InMemoryConversationRepository - 内存存储（适合测试、单机场景）</li>
 *   <li>DatabaseConversationRepository - 数据库存储（适合持久化、集群场景）</li>
 *   <li>AgentScopeRepository - AgentScope框架存储（适合与框架集成）</li>
 *   <li>CompositeRepository - 组合存储（支持缓存+数据库降级）</li>
 * </ul>
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface ConversationRepository {

    /**
     * 保存会话（插入或更新）
     * 
     * @param conversation 会话实体
     * @return 保存成功返回 true
     */
    boolean save(ConversationEntity conversation);

    /**
     * 根据 ID 查找会话
     * 
     * @param conversationId 会话 ID
     * @return 会话实体（可能为空）
     */
    Optional<ConversationEntity> findById(String conversationId);

    /**
     * 根据用户 ID 查找所有会话
     * 
     * @param userId 用户 ID
     * @return 会话列表
     */
    List<ConversationEntity> findByUserId(String userId);

    /**
     * 根据 Agent 名称查找所有会话
     * 
     * @param agentName Agent 名称
     * @return 会话列表
     */
    List<ConversationEntity> findByAgentName(String agentName);

    /**
     * 删除会话
     * 
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    boolean deleteById(String conversationId);

    /**
     * 检查会话是否存在
     * 
     * @param conversationId 会话 ID
     * @return 存在返回 true
     */
    boolean existsById(String conversationId);

    /**
     * 统计用户的会话数量
     * 
     * @param userId 用户 ID
     * @return 会话数量
     */
    long countByUserId(String userId);

    /**
     * 统计所有会话数量
     * 
     * @return 会话总数量
     */
    long count();

    /**
     * 清空所有会话（谨慎使用）
     */
    void deleteAll();

    /**
     * 获取存储类型名称
     * 
     * @return 存储类型（如 "memory", "database", "agentscope"）
     */
    String getStorageType();
}
