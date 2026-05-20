package io.yunxi.platform.framework.profile;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 节点画像 Mapper
 *
 * @author yunxi-agent-platform
 */
@Mapper
public interface NodeProfileMapper {

    /**
     * 插入或更新节点画像
     *
     * @param profile 节点画像实体
     */
    void insertOrUpdate(NodeProfile profile);

    /**
     * 根据客户端ID查询画像
     *
     * @param clientId 客户端ID
     * @return 节点画像
     */
    NodeProfile selectByClientId(@Param("clientId") String clientId);

    /**
     * 根据用户ID查询画像列表
     *
     * @param userId 用户ID
     * @return 节点画像列表
     */
    List<NodeProfile> selectByUserId(@Param("userId") String userId);

    /**
     * 根据标签查询画像列表
     *
     * @param tag 标签
     * @return 节点画像列表
     */
    List<NodeProfile> selectByTag(@Param("tag") String tag);

    /**
     * 查询所有在线节点
     *
     * @return 在线节点画像列表
     */
    List<NodeProfile> selectOnlineNodes();

    /**
     * 更新节点在线状态
     *
     * @param clientId     客户端ID
     * @param isOnline     是否在线
     * @param lastOnlineAt 最后在线时间
     */
    void updateOnlineStatus(@Param("clientId") String clientId,
                            @Param("isOnline") boolean isOnline,
                            @Param("lastOnlineAt") java.time.LocalDateTime lastOnlineAt);
}
