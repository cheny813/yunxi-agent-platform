package io.yunxi.platform.agent.profile;

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
     * 新增或更新节点画像（按 clientId 幂等写入）。
     *
     * @param profile 节点画像实体
     */
    void insertOrUpdate(NodeProfile profile);

    /**
     * 按客户端 ID 查询节点画像。
     *
     * @param clientId 客户端标识
     * @return 命中的节点画像，不存在时返回 null
     */
    NodeProfile selectByClientId(@Param("clientId") String clientId);

    /**
     * 按用户 ID 查询其名下全部节点画像。
     *
     * @param userId 用户标识
     * @return 节点画像列表
     */
    List<NodeProfile> selectByUserId(@Param("userId") String userId);

    /**
     * 按标签查询匹配的节点画像。
     *
     * @param tag 标签值
     * @return 命中的节点画像列表
     */
    List<NodeProfile> selectByTag(@Param("tag") String tag);

    /**
     * 查询当前在线的全部节点画像。
     *
     * @return 在线节点画像列表
     */
    List<NodeProfile> selectOnlineNodes();

    /**
     * 更新指定节点的在线状态与最近在线时间。
     *
     * @param clientId 客户端标识
     * @param isOnline 是否在线
     * @param lastOnlineAt 最近在线时间
     */
    void updateOnlineStatus(@Param("clientId") String clientId,
                            @Param("isOnline") boolean isOnline,
                            @Param("lastOnlineAt") java.time.LocalDateTime lastOnlineAt);
}
