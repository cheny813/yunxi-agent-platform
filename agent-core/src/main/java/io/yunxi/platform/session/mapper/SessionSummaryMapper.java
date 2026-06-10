package io.yunxi.platform.session.mapper;

import io.yunxi.platform.session.entity.SessionSummaryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话摘要 Mapper
 * <p>
 * 负责会话摘要的数据库操作
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Mapper
public interface SessionSummaryMapper {

    /** 插入或更新会话摘要 */
    int save(SessionSummaryEntity entity);

    /** 根据会话ID查询摘要 */
    SessionSummaryEntity findByConversationId(String conversationId);

    /** 根据会话ID和摘要类型查询 */
    SessionSummaryEntity findByConversationIdAndType(@Param("conversationId") String conversationId,
                                                       @Param("summaryType") String summaryType);

    /** 根据用户ID查询所有摘要 */
    List<SessionSummaryEntity> findByUserId(String userId);

    /** 根据用户ID和Agent名称查询摘要 */
    List<SessionSummaryEntity> findByUserIdAndAgentName(@Param("userId") String userId,
                                                           @Param("agentName") String agentName);

    /** 删除会话摘要 */
    int deleteByConversationId(String conversationId);

    /** 批量删除会话摘要 */
    int deleteByConversationIdIn(@Param("conversationIds") List<String> conversationIds);

    /** 查询指定时间之前的摘要 */
    List<SessionSummaryEntity> findByCreatedAtBefore(LocalDateTime beforeTime);

    /** 创建session_summaries表 */
    void createSessionSummariesTableIfNotExists();
}
