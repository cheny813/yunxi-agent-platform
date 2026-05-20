package io.yunxi.platform.shared.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.yunxi.platform.shared.entity.ConversationEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话数据访问层（MyBatis Mapper）
 * <p>
 * 负责对 Conversations 表进行 CRUD 操作
 * 提供会话的查询、保存、删除、过期清理等功能
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Mapper
public interface ConversationMapper {

    /**
     * 插入或更新会话
     * <p>
     * 使用 INSERT ... ON DUPLICATE KEY UPDATE 语法实现幂等操作
     * 如果 id 已存在则更新，否则插入新记录
     * </p>
     *
     * @param entity 会话实体，必须包含 id 字段
     * @return 影响行数（>0 表示插入或更新成功）
     */
    int save(ConversationEntity entity);

    /**
     * 根据 id 删除会话
     *
     * @param id 会话 ID（主键）
     * @return 影响行数
     */
    int deleteById(String id);

    /**
     * 根据 id 查询会话
     *
     * @param id 会话 ID
     * @return 会话实体，如果不存在返回 null
     */
    ConversationEntity findById(String id);

    /**
     * 查询所有会话
     *
     * @return 会话列表
     */
    List<ConversationEntity> findAll();

    /**
     * 根据 Agent 名称查询会话列表
     *
     * @param agentName Agent 名称
     * @return 会话列表
     */
    List<ConversationEntity> findByAgentName(String agentName);

    /**
     * 根据 userId 查询会话列表
     *
     * @param userId 用户 ID
     * @return 会话列表
     */
    List<ConversationEntity> findByUserId(String userId);

    /**
     * 根据 userId 和 agentName 查询会话列表
     *
     * @param userId    用户 ID
     * @param agentName Agent 名称
     * @return 会话列表
     */
    List<ConversationEntity> findByUserIdAndAgentName(@Param("userId") String userId,
            @Param("agentName") String agentName);

    /**
     * 查询指定时间之前的所有会话
     *
     * @param beforeTime 时间点
     * @return 会话列表
     */
    List<ConversationEntity> findByCreateTimeBefore(LocalDateTime beforeTime);

    /**
     * 删除指定时间之前的会话
     *
     * @param beforeTime 时间点
     * @return 删除的记录数
     */
    int deleteByCreateTimeBefore(LocalDateTime beforeTime);

    /**
     * 批量插入会话
     *
     * @param entities 会话实体列表
     * @return 插入的记录数
     */
    int insertBatch(@Param("list") List<ConversationEntity> entities);

    /**
     * 根据 userId 和 agentName 统计会话数量
     *
     * @param userId    用户 ID
     * @param agentName Agent 名称
     * @return 会话数量
     */
    int countByUserIdAndAgentName(@Param("userId") String userId,
            @Param("agentName") String agentName);

    /**
     * 根据用户 ID 统计会话数量
     *
     * @param userId 用户 ID
     * @return 会话数量
     */
    long countByUserId(@Param("userId") String userId);

    /**
     * 统计所有会话数量
     *
     * @return 会话总数量
     */
    long count();

    // ==================== DDL 操作 ====================

    /**
     * 创建 agents 表（如果不存在）
     */
    void createAgentsTableIfNotExists();

    /**
     * 创建 conversations 表（如果不存在）
     */
    void createConversationsTableIfNotExists();

    /**
     * 创建 tool_configs 表（如果不存在）
     */
    void createToolConfigsTableIfNotExists();

    /**
     * 创建 chat_logs 表（如果不存在）
     */
    void createChatLogsTableIfNotExists();
}
