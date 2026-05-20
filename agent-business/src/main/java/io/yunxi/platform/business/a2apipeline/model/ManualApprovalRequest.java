package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

/**
 * ManualApprovalRequest 人工审核请求
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class ManualApprovalRequest {

    /**
     * 流水线实例ID
     */
    private String instanceId;

    /**
     * 是否通过
     */
    private boolean approved;

    /**
     * 审核意见
     */
    private String comment;

    /**
     * 审核人
     */
    private String approver;

    /**
     * 审核时间
     */
    private java.time.Instant approvedAt;
}