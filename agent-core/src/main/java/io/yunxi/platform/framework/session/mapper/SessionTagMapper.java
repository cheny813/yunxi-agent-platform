package io.yunxi.platform.framework.session.mapper;

import io.yunxi.platform.framework.session.entity.SessionTagEntity;
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

    /**
     * 插入或更新标签
     *
     * @param entity 标签实体
     * @return 影响行数
     */
    int save(SessionTagEntity entity);

    /**
     * 批量插入标签
     *
     * @param tags 标签列表
     * @return 影响行数
     */
    int insertBatch(@Param("list") List<SessionTagEntity> tags);

    /**
     * 根据会话ID查询所有标签
     *
     * @param conversationId 会话ID
     * @return 标签列表
     */
    List<SessionTagEntity> findByConversationId(String conversationId);

    /**
     * 根据用户ID查询标签
     *
     * @param userId 用户ID
     * @return 标签列表
     */
    List<SessionTagEntity> findByUserId(String userId);

    /**
     * 根据用户ID和Agent名称查询标签
     *
     * @param userId    用户ID
     * @param agentName Agent名称
     * @return 标签列表
     */
    List<SessionTagEntity> findByUserIdAndAgentName(@Param("userId") String userId,
                                                      @Param("agentName") String agentName);

    /**
     * 根据标签类型查询
     *
     * @param tagType 标签类型
     * @return 标签列表
     */
    List<SessionTagEntity> findByTagType(String tagType);

    /**
     * 删除会话标签
     *
     * @param conversationId 会话ID
     * @return 影响行数
     */
    int deleteByConversationId(String conversationId);

    /**
     * 批量删除会话标签
     *
     * @param conversationIds 会话ID列表
     * @return 影响行数
     */
    int deleteByConversationIdIn(@Param("conversationIds") List<String> conversationIds);

    /**
     * 全文搜索标签值
     * 使用MySQL FULLTEXT索引
     *
     * @param query 搜索查询
     * @param limit 结果限制
     * @return 标签列表
     */
    List<SessionTagEntity> fullTextSearch(@Param("query") String query,
                                           @Param("limit") int limit);

    /**
     * 创建session_tags表
     */
    void createSessionTagsTableIfNotExists();

    /**
     * 创建FULLTEXT全文索引
     */
    void createFullTextIndexIfNotExists();
}
