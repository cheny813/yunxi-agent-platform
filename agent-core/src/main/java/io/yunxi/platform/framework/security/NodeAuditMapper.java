package io.yunxi.platform.framework.security;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 节点命令审计日志 Mapper
 *
 * @author yunxi-agent-platform
 */
@Mapper
public interface NodeAuditMapper {

    /**
     * 插入审计日志
     *
     * @param audit 审计日志数据
     */
    void insertAudit(@Param("audit") Map<String, Object> audit);

    /**
     * 查询审计日志
     *
     * @param operatorId      操作人ID
     * @param targetClientId  目标客户端ID
     * @param safetyLevel     安全级别
     * @param limit           返回数量限制
     * @return 审计日志列表
     */
    List<Map<String, Object>> queryAudit(@Param("operatorId") String operatorId,
                                          @Param("targetClientId") String targetClientId,
                                          @Param("safetyLevel") String safetyLevel,
                                          @Param("limit") int limit);
}
