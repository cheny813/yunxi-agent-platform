package io.yunxi.platform.shared.config;

import lombok.Data;

/**
 * approval 节点的人工审批配置。
 */
@Data
public class ApprovalConfig {

    /** 审批人类型（如 user / role） */
    private String approverType;

    /** 审批人引用（UUID 或标识） */
    private String approverRef;

    /** 审批提示语 */
    private String prompt;
}
