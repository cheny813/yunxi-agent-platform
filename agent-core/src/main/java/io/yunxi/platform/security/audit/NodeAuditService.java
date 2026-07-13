package io.yunxi.platform.security.audit;

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
 * 安全级别使用字符串（"safe"/"warning"/"blocked"）标识。
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
     * 记录一条完整的节点命令审计日志。
     *
     * @param requestId       请求唯一标识（可为 null）
     * @param operatorId      操作人ID
     * @param targetClientId  目标客户端ID
     * @param targetNodeType  目标节点类型（可为 null）
     * @param commandType     命令类型
     * @param commandText     命令内容
     * @param safetyLevel     安全级别（"safe"/"warning"/"blocked"）
     * @param status          执行状态
     * @param confirmed       是否需要人工确认
     * @param resultSummary   执行结果摘要（可为 null）
     */
    public void record(String requestId, String operatorId, String targetClientId,
            String targetNodeType, String commandType, String commandText,
            String safetyLevel, String status, boolean confirmed,
            String resultSummary) {
        try {
            Map<String, Object> audit = new HashMap<>();
            audit.put("requestId", requestId);
            audit.put("operatorId", operatorId);
            audit.put("targetClientId", targetClientId);
            audit.put("targetNodeType", targetNodeType);
            audit.put("commandType", commandType);
            audit.put("commandText", commandText);
            audit.put("safetyLevel", safetyLevel);
            audit.put("status", status);
            audit.put("confirmed", confirmed ? 1 : 0);
            audit.put("resultSummary", resultSummary);

            auditMapper.insertAudit(audit);
        } catch (Exception e) {
            log.error("[AuditService] 记录审计日志失败", e);
        }
    }

    /**
     * 记录一条简化审计日志，缺失字段自动填充默认值。
     * <p>等价于 {@link #record(String, String, String, String, String, String, String, String, boolean, String)}
     * 以 null/默认参数调用（命令类型固定为 "execute"，无需人工确认）。</p>
     *
     * @param operatorId    操作人ID
     * @param targetClientId 目标客户端ID
     * @param commandText   命令内容
     * @param safetyLevel   安全级别（"safe"/"warning"/"blocked"）
     * @param status        执行状态
     */
    public void record(String operatorId, String targetClientId, String commandText,
            String safetyLevel, String status) {
        record(null, operatorId, targetClientId, null, "execute",
                commandText, safetyLevel, status, false, null);
    }

    /**
     * 查询审计日志（按操作人/目标/安全级别过滤，限制返回条数）。
     *
     * @param operatorId    操作人ID（可为 null，表示不过滤）
     * @param targetClientId 目标客户端ID（可为 null）
     * @param safetyLevel   安全级别（可为 null）
     * @param limit         返回条数上限（最多 100 条）
     * @return 审计日志记录列表；查询异常时返回空列表
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
