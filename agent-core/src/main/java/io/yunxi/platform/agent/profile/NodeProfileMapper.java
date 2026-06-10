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

    void insertOrUpdate(NodeProfile profile);
    NodeProfile selectByClientId(@Param("clientId") String clientId);
    List<NodeProfile> selectByUserId(@Param("userId") String userId);
    List<NodeProfile> selectByTag(@Param("tag") String tag);
    List<NodeProfile> selectOnlineNodes();
    void updateOnlineStatus(@Param("clientId") String clientId,
                            @Param("isOnline") boolean isOnline,
                            @Param("lastOnlineAt") java.time.LocalDateTime lastOnlineAt);
}
