package io.yunxi.platform.framework.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点命令审计服务
 *
 * <p>
 * 记录所有通过 NodeTool 执行的命令，包括操作人、目标、命令内容、安全级别和执行状态。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class NodeAuditService {

    /** 审计日志 Mapper */
    private final NodeAuditMapper auditMapper;

    /**
     * 构造函数
     *
     * @param auditMapper 审计日志 Mapper
     */
    public NodeAuditService(NodeAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /**
     * 记录审计日志
     */
    public void record(String requestId, String operatorId, String targetClientId,
            String targetNodeType, String commandType, String commandText,
            CommandSafety safetyLevel, String status, boolean confirmed,
            String resultSummary) {
        try {
            Map<String, Object> audit = new HashMap<>();
            audit.put("requestId", requestId);
            audit.put("operatorId", operatorId);
            audit.put("targetClientId", targetClientId);
            audit.put("targetNodeType", targetNodeType);
            audit.put("commandType", commandType);
            audit.put("commandText", commandText);
            audit.put("safetyLevel", safetyLevel.getCode());
            audit.put("status", status);
            audit.put("confirmed", confirmed ? 1 : 0);
            audit.put("resultSummary", resultSummary);

            auditMapper.insertAudit(audit);
        } catch (Exception e) {
            log.error("[AuditService] 记录审计日志失败", e);
        }
    }

    /**
     * 记录简单审计（自动填充默认值）
     */
    public void record(String operatorId, String targetClientId, String commandText,
            CommandSafety safetyLevel, String status) {
        record(null, operatorId, targetClientId, null, "execute",
                commandText, safetyLevel, status, false, null);
    }

    /**
     * 查询审计日志
     */
    public List<Map<String, Object>> query(String operatorId, String targetClientId,
            String safetyLevel, int limit) {
        try {
            return auditMapper.queryAudit(operatorId, targetClientId, safetyLevel,
                    Math.min(limit, 100));
        } catch (Exception e) {
            log.error("[AuditService] 查询审计日志失败", e);
            return List.of();
        }
    }
}
