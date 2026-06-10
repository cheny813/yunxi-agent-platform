package io.yunxi.platform.session.mapper;

import io.yunxi.platform.session.entity.SessionTagEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话标签 Mapper
 * <p>
 * 负责会话标签的数据库操作，支持MySQL FULLTEXT全文搜索
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Mapper
public interface SessionTagMapper {

    /** 插入或更新标签 */
    int save(SessionTagEntity entity);

    /** 批量插入标签 */
    int insertBatch(@Param("list") List<SessionTagEntity> tags);

    /** 根据会话ID查询所有标签 */
    List<SessionTagEntity> findByConversationId(String conversationId);

    /** 根据用户ID查询标签 */
    List<SessionTagEntity> findByUserId(String userId);

    /** 根据用户ID和Agent名称查询标签 */
    List<SessionTagEntity> findByUserIdAndAgentName(@Param("userId") String userId,
                                                      @Param("agentName") String agentName);

    /** 根据标签类型查询 */
    List<SessionTagEntity> findByTagType(String tagType);

    /** 删除会话标签 */
    int deleteByConversationId(String conversationId);

    /** 批量删除会话标签 */
    int deleteByConversationIdIn(@Param("conversationIds") List<String> conversationIds);

    /** 全文搜索标签值 */
    List<SessionTagEntity> fullTextSearch(@Param("query") String query, @Param("limit") int limit);

    /** 创建session_tags表 */
    void createSessionTagsTableIfNotExists();

    /** 创建FULLTEXT全文索引 */
    void createFullTextIndexIfNotExists();
}
